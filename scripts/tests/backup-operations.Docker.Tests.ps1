param()
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'backup-operations.ps1')
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$tag = [Guid]::NewGuid().ToString('N')
$fixture = Join-Path $workspace "build\backup-restore-docker-$tag"
$script:TestDatabaseContainer = "attendra-backup-test-db-$tag"
$script:TestFaceContainer = "attendra-backup-test-faces-$tag"
$testVolume = "attendra-backup-test-volume-$tag"
$script:DockerProgram = (Get-Command docker.exe).Source
$script:Count = 0
function Assert($Condition, $Message) { if (!$Condition) { throw "FAIL: $Message" }; $script:Count++ }
# Rewrite only the two fixed container identities used by the implementation. No customer target is reachable.
function docker {
    $command = [string]$args[0]
    $index = -1
    $mapped = @($args | ForEach-Object {
        $index++
        $value = [string]$_
        $containerPosition = ($command -in @('inspect', 'port') -and $index -eq 1) -or ($command -eq 'exec' -and $index -le 2)
        if ($value -eq 'hic_postgres' -and $containerPosition) { $script:TestDatabaseContainer }
        elseif ($value.StartsWith('hic_postgres:')) { $script:TestDatabaseContainer + $value.Substring('hic_postgres'.Length) }
        elseif ($value -eq 'hic_backend' -and $containerPosition) { $script:TestFaceContainer }
        elseif ($value.StartsWith('hic_backend:')) { $script:TestFaceContainer + $value.Substring('hic_backend'.Length) }
        else { $value }
    })
    $input | & $script:DockerProgram @mapped 2>> (Join-Path $fixture 'docker-errors.txt')
    $global:LASTEXITCODE = $LASTEXITCODE
}
function Put($Path, $Value) { [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false)) }
function Stop-BackupApplications { }
function Start-BackupApplications { }
$script:HealthFailure = $false
function Wait-BackupHealth { if ($script:HealthFailure) { $script:HealthFailure = $false; throw 'simulated boot failure' } }
try {
    New-Item -ItemType Directory -Path $fixture -Force | Out-Null
    Put (Join-Path $fixture '.env') "POSTGRES_PASSWORD=fixture-password`nJWT_SECRET=fixture-old-session`nENCRYPTION_SECRET_KEY=fixture-old-encryption`nISAPI_API_KEY=fixture-old-api"
    Initialize-BackupHost $fixture
    & $script:DockerProgram run -d --name $script:TestDatabaseContainer --network none --tmpfs /var/lib/postgresql/data -e POSTGRES_DB=hic_backend -e POSTGRES_USER=hic_user -e POSTGRES_PASSWORD=fixture-only postgres:15-alpine > $null
    if ($LASTEXITCODE) { throw 'Fixture PostgreSQL start failed' }
    & $script:DockerProgram volume create $testVolume > $null
    & $script:DockerProgram run -d --name $script:TestFaceContainer --network none --mount "type=volume,source=$testVolume,target=/app/uploads/faces" --entrypoint sh hr_erp_attendra-backend -c 'sleep 600' > $null
    if ($LASTEXITCODE) { throw 'Fixture photo container start failed' }
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        & $script:DockerProgram exec $script:TestDatabaseContainer pg_isready -h 127.0.0.1 -U hic_user -d hic_backend > $null 2>&1
        if (!$LASTEXITCODE) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'Fixture database not ready' }
    $null = Invoke-BackupSql 'CREATE DATABASE hic_isapi OWNER hic_user;'
    $null = Invoke-BackupSql "CREATE TABLE flyway_schema_history_backend(installed_rank integer, version varchar(50), success boolean); INSERT INTO flyway_schema_history_backend VALUES (42, '42', true); CREATE TABLE fixture(value text); INSERT INTO fixture VALUES ('archived-backend');" 'hic_backend'
    $null = Invoke-BackupSql "CREATE TABLE fixture(value text); INSERT INTO fixture VALUES ('archived-isapi');" 'hic_isapi'
    & $script:DockerProgram exec $script:TestFaceContainer sh -c 'echo archived-photo > /app/uploads/faces/photo.jpg'
    $archive = Join-Path $fixture 'original.zip'
    New-BackupSnapshot (Join-Path $fixture 'original') $archive
    $null = Invoke-BackupSql "UPDATE fixture SET value='current-backend';" 'hic_backend'
    $null = Invoke-BackupSql "UPDATE fixture SET value='current-isapi';" 'hic_isapi'
    & $script:DockerProgram exec $script:TestFaceContainer sh -c 'echo current-photo > /app/uploads/faces/photo.jpg'
    Put (Join-Path $fixture '.env') "POSTGRES_PASSWORD=fixture-current-password`nJWT_SECRET=fixture-current-session`nENCRYPTION_SECRET_KEY=fixture-current-encryption`nISAPI_API_KEY=fixture-current-api"
    $job = Join-Path $script:OperationsRoot ([Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $job | Out-Null
    $null = Expand-VerifiedBackup $archive (Join-Path $job 'incoming')
    Invoke-BackupOperation $job 'RESTORE'
    Assert ((Get-Content (Join-Path $job 'status.json') -Raw | ConvertFrom-Json).state -eq 'SUCCESS') 'real restore completes'
    Assert ((Invoke-BackupSql 'SELECT value FROM fixture;' 'hic_backend') -eq 'archived-backend') 'backend dump restored'
    Assert ((Invoke-BackupSql 'SELECT value FROM fixture;' 'hic_isapi') -eq 'archived-isapi') 'ISAPI dump restored'
    $photo = & $script:DockerProgram exec $script:TestFaceContainer cat /app/uploads/faces/photo.jpg
    Assert ($photo -eq 'archived-photo') 'photo volume restored'
    $config = Read-EnvironmentFile (Join-Path $fixture '.env')
    Assert ($config['ENCRYPTION_SECRET_KEY'] -eq 'fixture-old-encryption') 'archive configuration restored'
    Assert ($config['POSTGRES_PASSWORD'] -eq 'fixture-current-password') 'host credential retained'
    Assert (Test-Path (Join-Path $job 'before-restore.zip')) 'automatic safety archive retained'
    $null = Expand-VerifiedBackup (Join-Path $job 'before-restore.zip') (Join-Path $job 'safety-verified')
    Assert ((Get-Content (Join-Path $job 'safety-verified\faces\photo.jpg')).Trim() -eq 'current-photo') 'safety archive contains pre-restore photo'

    $null = Invoke-BackupSql "UPDATE fixture SET value='rollback-backend';" 'hic_backend'
    $null = Invoke-BackupSql "UPDATE fixture SET value='rollback-isapi';" 'hic_isapi'
    & $script:DockerProgram exec $script:TestFaceContainer sh -c 'echo rollback-photo > /app/uploads/faces/photo.jpg'
    Put (Join-Path $fixture '.env') "POSTGRES_PASSWORD=fixture-current-password`nJWT_SECRET=rollback-session`nENCRYPTION_SECRET_KEY=rollback-key`nISAPI_API_KEY=rollback-api"
    $failedJob = Join-Path $script:OperationsRoot ([Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $failedJob | Out-Null
    $null = Expand-VerifiedBackup $archive (Join-Path $failedJob 'incoming')
    $script:HealthFailure = $true
    Invoke-BackupOperation $failedJob 'RESTORE'
    Assert ((Get-Content (Join-Path $failedJob 'status.json') -Raw | ConvertFrom-Json).state -eq 'ERROR') 'simulated failed boot reported'
    Assert ((Invoke-BackupSql 'SELECT value FROM fixture;' 'hic_backend') -eq 'rollback-backend') 'original backend DB restored after failed boot'
    Assert ((Invoke-BackupSql 'SELECT value FROM fixture;' 'hic_isapi') -eq 'rollback-isapi') 'original ISAPI DB restored after failed boot'
    $photo = & $script:DockerProgram exec $script:TestFaceContainer cat /app/uploads/faces/photo.jpg
    Assert ($photo -eq 'rollback-photo') 'original photo restored after failed boot'
    Assert ((Read-EnvironmentFile (Join-Path $fixture '.env'))['ENCRYPTION_SECRET_KEY'] -eq 'rollback-key') 'original configuration restored after failed boot'
    Write-Output "PASS: $script:Count real PostgreSQL backup/restore assertions; disposable fixtures only"
} catch {
    Write-Output $_.ScriptStackTrace
    if (Test-Path (Join-Path $fixture 'docker-errors.txt')) { Get-Content (Join-Path $fixture 'docker-errors.txt') -Tail 8 }
    throw
} finally {
    & $script:DockerProgram rm -f $script:TestFaceContainer $script:TestDatabaseContainer > $null 2>&1
    if (!$testVolume.StartsWith('attendra-backup-test-volume-')) { throw 'Unsafe test volume cleanup' }
    & $script:DockerProgram volume rm $testVolume > $null 2>&1
    $resolved = [IO.Path]::GetFullPath($fixture)
    $allowed = [IO.Path]::GetFullPath((Join-Path $workspace 'build')) + [IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test folder cleanup' }
    Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
}
