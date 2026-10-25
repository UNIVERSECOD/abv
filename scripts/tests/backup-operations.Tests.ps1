param()
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'backup-operations.ps1')
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$fixtureRoot = Join-Path $workspace ('build\backup-operations-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
$script:Passed = 0
function Assert($Condition, $Message) {
    if (!$Condition) { throw "FAIL: $Message" }
    $script:Passed++
}
function Rejects([scriptblock]$Action, $Message) {
    $caught = $false
    try { & $Action } catch { $caught = $true }
    Assert $caught $Message
}
function Put($Path, $Value) { [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false)) }
try {
    $project = Join-Path $fixtureRoot 'project'
    New-Item -ItemType Directory -Path $project | Out-Null
    Put (Join-Path $project '.env') "POSTGRES_PASSWORD=host-test-only`nJWT_SECRET=original-test-only`nENCRYPTION_SECRET_KEY=original-test-key`nISAPI_API_KEY=original-api-key`nCORS_ALLOWED_ORIGINS=http://localhost:3001"
    Initialize-BackupHost $project
    $script:FakeFace = 'original-photo'
    $script:Calls = [Collections.Generic.List[string]]::new()
    function Export-BackupDatabase($Database, $Destination) { Put $Destination "PGDMP-fixture-$Database" }
    function Copy-BackupFaces($Destination) { New-Item -ItemType Directory $Destination | Out-Null; Put (Join-Path $Destination 'photo.jpg') $script:FakeFace }
    function Invoke-BackupSql($Sql, $Database = 'postgres') { return '42' }
    $archive = Join-Path $fixtureRoot 'valid.zip'
    New-BackupSnapshot (Join-Path $fixtureRoot 'snapshot') $archive
    $preview = Expand-VerifiedBackup $archive (Join-Path $fixtureRoot 'expanded')
    Assert ($preview.configurationIncluded -and $preview.files -ge 5) 'snapshot includes databases, photo and configuration'
    Assert ((Get-Content (Join-Path $fixtureRoot 'expanded\.env.backup') -Raw).Contains('original-test-key')) 'configuration keys survive archive'

    $bad = Join-Path $fixtureRoot 'traversal.zip'
    $zip = [IO.Compression.ZipFile]::Open($bad, 'Create')
    try {
        foreach ($name in @('manifest.json', '../escape.txt')) {
            $writer = [IO.StreamWriter]::new($zip.CreateEntry($name).Open())
            $writer.Write('{}'); $writer.Dispose()
        }
    } finally { $zip.Dispose() }
    Rejects { Expand-VerifiedBackup $bad (Join-Path $fixtureRoot 'traversal') } 'ZIP traversal rejected'
    Assert (!(Test-Path (Join-Path $fixtureRoot 'escape.txt'))) 'ZIP cannot write outside extraction directory'

    $corrupt = Join-Path $fixtureRoot 'corrupt.zip'
    Copy-Item $archive $corrupt
    $zip = [IO.Compression.ZipFile]::Open($corrupt, 'Update')
    try {
        $zip.GetEntry('faces/photo.jpg').Delete()
        $writer = [IO.StreamWriter]::new($zip.CreateEntry('faces/photo.jpg').Open()); $writer.Write('changed'); $writer.Dispose()
    } finally { $zip.Dispose() }
    Rejects { Expand-VerifiedBackup $corrupt (Join-Path $fixtureRoot 'corrupt') } 'checksum detects altered photo'

    # Exercise the real container-control helpers, but intercept Docker before any host call.
    $script:DockerTrace = [Collections.Generic.List[string]]::new()
    function docker {
        $script:DockerTrace.Add(($args -join '|'))
        $global:LASTEXITCODE = 0
        if ($args[0] -eq 'inspect') { return ('sha256:' + ('a' * 64)) }
        if ($args[0] -eq 'port') {
            $value = if ($args[1] -eq 'hic_backend') { '127.0.0.1:8082' } else { '127.0.0.1:8081' }
            & powershell.exe -NoProfile -Command "[Console]::WriteLine('$value')"
            $global:LASTEXITCODE = $LASTEXITCODE
            return
        }
        if ($args[0] -eq 'exec') { return 'fixture-api-key' }
        $global:LASTEXITCODE = 0
    }
    $global:LASTEXITCODE = 0
    Stop-BackupApplications
    Assert (@($script:DockerTrace | Where-Object { $_.EndsWith('|stop|backend|isapi') }).Count -eq 1) 'stop targets exactly backend and ISAPI; PostgreSQL and web remain running'
    Start-BackupApplications
    Assert ($script:DockerTrace.Contains('start|hic_isapi|hic_backend')) 'ordinary backup resumes existing containers without changing images'
    Assert (@($script:DockerTrace | Where-Object { $_.EndsWith('|exec|-T|frontend|nginx|-s|reload') }).Count -eq 1) 'all nginx reload arguments are retained'
    Start-BackupApplications -Reconfigure
    Assert (@($script:DockerTrace | Where-Object { $_.Contains('|up|-d|--no-deps|--no-build|--pull|never|isapi|backend') }).Count -eq 1) 'restore recreates only application services using installed images without pulling or rebuilding'
    $script:HttpTrace = [Collections.Generic.List[string]]::new()
    function Invoke-WebRequest($Uri) { $script:HttpTrace.Add([string]$Uri); return [pscustomobject]@{ StatusCode = 200 } }
    Wait-BackupHealth
    Assert ($script:HttpTrace.Contains('http://127.0.0.1:8082/api/health')) 'native process port output is fully consumed before checking exit code'
    Assert ($script:HttpTrace.Contains('http://127.0.0.1:8081/api/devices')) 'ISAPI database endpoint is checked without contacting physical devices'
    Remove-Item function:Invoke-WebRequest
    Remove-Item function:docker

    $script:Databases = @{}
    function Stop-BackupApplications { $script:Calls.Add('stop') }
    function Start-BackupApplications { $script:Calls.Add('start') }
    function Wait-BackupHealth {
        $script:Calls.Add('health')
        if ($script:FailHealth) { $script:FailHealth = $false; throw 'simulated health failure' }
    }
    function Import-StagedDatabase($Database, $Dump) { $script:Calls.Add('import'); $script:Databases[$Database] = 'restored' }
    function Rename-BackupDatabase($From, $To) {
        $script:Calls.Add('rename')
        if (!$script:Databases.ContainsKey($From) -or $script:Databases.ContainsKey($To)) { throw 'simulated rename conflict' }
        $script:Databases[$To] = $script:Databases[$From]; $script:Databases.Remove($From)
    }
    function Test-BackupDatabase($Name) { return $script:Databases.ContainsKey($Name) }
    function Install-BackupFaces($Source) { $script:InstalledFace = Get-Content (Join-Path $Source 'photo.jpg') -Raw }
    $script:FailHealth = $false
    $createJob = Join-Path $script:OperationsRoot ([Guid]::NewGuid().ToString())
    New-Item -ItemType Directory $createJob | Out-Null
    Invoke-BackupOperation $createJob 'CREATE'
    Assert ((Get-Content (Join-Path $createJob 'status.json') -Raw | ConvertFrom-Json).state -eq 'SUCCESS') 'create operation completes'
    $createId = [IO.Path]::GetFileName($createJob)
    Assert (Test-Path (Join-Path $project "backups\attendra-operation-$createId\attendra-backup-$createId.zip")) 'archive is persisted in an operation-specific protected backup directory'
    Assert ($script:Calls[0] -eq 'stop' -and $script:Calls[1] -eq 'start') 'consistent snapshot pauses and resumes applications'

    foreach ($rollback in @($false, $true)) {
        $job = Join-Path $script:OperationsRoot ([Guid]::NewGuid().ToString())
        New-Item -ItemType Directory $job | Out-Null
        $null = Expand-VerifiedBackup $archive (Join-Path $job 'incoming')
        Put (Join-Path $project '.env') "POSTGRES_PASSWORD=current-host-password`nJWT_SECRET=current-session-key`nENCRYPTION_SECRET_KEY=current-encryption-key`nISAPI_API_KEY=current-api-key`nCORS_ALLOWED_ORIGINS=http://localhost:3001"
        $script:FakeFace = 'current-photo'
        $script:Databases = @{ hic_backend = 'current-backend'; hic_isapi = 'current-isapi' }
        $script:Calls.Clear(); $script:FailHealth = $rollback
        Invoke-BackupOperation $job 'RESTORE'
        Assert (Test-Path (Join-Path $job 'before-restore.zip')) 'automatic current backup exists before every restore'
        Assert ($script:Calls.IndexOf('import') -lt $script:Calls.IndexOf('stop')) 'archive is restored into test databases before live services stop'
        $safety = Expand-VerifiedBackup (Join-Path $job 'before-restore.zip') (Join-Path $job 'safety-check')
        Assert ($safety.configurationIncluded) 'automatic backup includes configuration'
        $state = Get-Content (Join-Path $job 'status.json') -Raw | ConvertFrom-Json
        $envFile = Read-EnvironmentFile (Join-Path $project '.env')
        if ($rollback) {
            Assert ($state.state -eq 'ERROR') 'failed health reports failure'
            Assert ($script:Databases.hic_backend -eq 'current-backend' -and $script:Databases.hic_isapi -eq 'current-isapi') 'failure rolls both original databases back'
            Assert ($envFile['ENCRYPTION_SECRET_KEY'] -eq 'current-encryption-key') 'failure restores original configuration'
            Assert ($script:InstalledFace -eq 'current-photo') 'failure restores original photos'
        } else {
            Assert ($state.state -eq 'SUCCESS') 'successful restore reported'
            Assert ($script:Databases.hic_backend -eq 'restored' -and $script:Databases.hic_isapi -eq 'restored') 'both databases swapped only after backup'
            Assert ($envFile['POSTGRES_PASSWORD'] -eq 'current-host-password') 'host database connection password preserved'
            Assert ($envFile['ENCRYPTION_SECRET_KEY'] -eq 'original-test-key') 'archived encryption configuration restored'
            Assert ($script:InstalledFace -eq 'original-photo') 'archived photos restored'
        }
        # Reset the fixture's intentionally persistent physical-device pause marker for next case.
        $marker = Join-Path $project 'runtime\device-removal-paused.json'
        if (Test-Path $marker) { Remove-Item -LiteralPath $marker }
    }
    # Native configuration merge is tested without touching SCM or a real native installation.
    $script:IsNative = $true
    $script:NativePath = Join-Path $project 'native.json'
    $script:Native = [pscustomobject]@{ installRoot='D:\CurrentInstall'; dataRoot=$project; webPort=3000; databasePort=5434; backendPort=8080; isapiPort=8081; databasePassword='current-db'; jwtSecret='current-jwt'; encryptionKey='current-key'; apiKey='current-api' }
    $nativeSource = Join-Path $fixtureRoot 'native-source'
    New-Item -ItemType Directory $nativeSource | Out-Null
    Write-OperationJson (Join-Path $nativeSource '.env.backup') @{ installRoot='E:\OldInstall'; dataRoot='E:\OldData'; webPort=9000; databasePort=9001; backendPort=9002; isapiPort=9003; databasePassword='old-db'; jwtSecret='old-jwt'; encryptionKey='old-key'; apiKey='old-api' }
    Install-BackupConfiguration $nativeSource
    Assert ($script:Native.installRoot -eq 'D:\CurrentInstall' -and $script:Native.dataRoot -eq $project) 'native host paths preserved'
    Assert ($script:Native.webPort -eq 3000 -and $script:Native.databasePort -eq 5434) 'native ports preserved'
    Assert ($script:Native.databasePassword -eq 'current-db') 'native database connection credential preserved'
    Assert ($script:Native.encryptionKey -eq 'old-key' -and $script:Native.apiKey -eq 'old-api') 'native application keys restored'
    Write-Output "PASS: $script:Passed backup-operation assertions (fixtures only)"
} finally {
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $allowed = [IO.Path]::GetFullPath((Join-Path $workspace 'build')) + [IO.Path]::DirectorySeparatorChar
    if (!$resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
