[CmdletBinding()]
param(
    [string]$ProjectRoot,
    [switch]$Force,
    [switch]$Required
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
$resolvedProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$runtimeDirectory = Join-Path $resolvedProjectRoot 'runtime'
$settingsFile = Join-Path $runtimeDirectory 'backup-settings.json'
$statusFile = Join-Path $runtimeDirectory 'backup-status.json'
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)

New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null

function Get-DirectoryBytes {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        return [long]0
    }
    $sum = Get-ChildItem -LiteralPath $Path -File -Recurse -ErrorAction SilentlyContinue |
        Measure-Object -Property Length -Sum
    if ($null -eq $sum.Sum) {
        return [long]0
    }
    return [long]$sum.Sum
}

function Read-PreviousStatus {
    if (-not (Test-Path -LiteralPath $statusFile -PathType Leaf)) {
        return $null
    }
    try {
        return Get-Content -LiteralPath $statusFile -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Write-BackupStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Message,
        [AllowNull()][object]$LastBackupAt,
        [long]$LastBackupBytes = 0,
        [long]$TotalBackupBytes = 0
    )

    # ConvertFrom-Json in PowerShell 7 can return DateTime. Keep the offset and
    # round-trip format before a string parameter could apply culture formatting.
    if ($LastBackupAt -is [DateTime] -or $LastBackupAt -is [DateTimeOffset]) {
        $LastBackupAt = $LastBackupAt.ToString('o')
    }
    $payload = [ordered]@{
        status = $Status
        lastBackupAt = $LastBackupAt
        message = $Message
        lastBackupBytes = $LastBackupBytes
        totalBackupBytes = $TotalBackupBytes
        updatedAt = [DateTimeOffset]::Now.ToString('o')
    } | ConvertTo-Json
    $temporaryStatus = "$statusFile.tmp"
    [System.IO.File]::WriteAllText($temporaryStatus, $payload, $utf8WithoutBom)
    Move-Item -LiteralPath $temporaryStatus -Destination $statusFile -Force
}

function Get-SafeBackupRoot {
    param([Parameter(Mandatory = $true)][string]$ConfiguredPath)

    if (-not [System.IO.Path]::IsPathRooted($ConfiguredPath) -or $ConfiguredPath.Contains('..')) {
        throw 'Backup qovluğunun tam və təhlükəsiz Windows yolu göstərilməlidir.'
    }
    $fullPath = [System.IO.Path]::GetFullPath($ConfiguredPath).TrimEnd('\')
    $pathRoot = [System.IO.Path]::GetPathRoot($fullPath).TrimEnd('\')
    if ($fullPath -eq $pathRoot) {
        throw 'Diskin kök qovluğu backup üçün seçilə bilməz.'
    }
    return $fullPath
}

function Get-CompletedBackup {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BackupRoot
    )

    # Fail closed: a timestamp alone does not identify an Attendra backup.
    try {
        $candidate = [System.IO.Path]::GetFullPath($Path)
        $parent = [System.IO.Path]::GetDirectoryName($candidate)
        if (-not $parent.Equals($BackupRoot, [System.StringComparison]::OrdinalIgnoreCase)) { return $null }
        $directory = Get-Item -LiteralPath $candidate -Force
        $backupDate = [DateTime]::MinValue
        if (-not $directory.PSIsContainer -or
            ($directory.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -or
            -not [DateTime]::TryParseExact($directory.Name, 'yyyyMMdd-HHmmss',
                [System.Globalization.CultureInfo]::InvariantCulture,
                [System.Globalization.DateTimeStyles]::None, [ref]$backupDate)) { return $null }

        $allowedNames = @('manifest.json', 'hic_backend.dump', 'hic_isapi.dump', 'faces', '.env.backup')
        foreach ($entry in Get-ChildItem -LiteralPath $candidate -Force) {
            if ($entry.Name -notin $allowedNames) { return $null }
        }
        # Reject linked files/directories, including links inside faces, before deletion.
        if (Get-ChildItem -LiteralPath $candidate -Force -Recurse |
                Where-Object { $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint } |
                Select-Object -First 1) { return $null }
        if (-not (Test-Path -LiteralPath (Join-Path $candidate 'faces') -PathType Container)) { return $null }

        $manifest = Get-Content -LiteralPath (Join-Path $candidate 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $createdAt = [DateTimeOffset]::MinValue
        if ($manifest.status -ne 'SUCCESS' -or $manifest.retentionDays -ne 183) { return $null }
        if ($manifest.createdAt -is [DateTime]) {
            # PowerShell 7 may deserialize ISO timestamps as DateTime rather than strings.
            $createdAt = [DateTimeOffset]$manifest.createdAt
        } elseif (-not [DateTimeOffset]::TryParse([string]$manifest.createdAt, [ref]$createdAt)) { return $null }
        $manifest.createdAt = $createdAt.ToString('o')
        foreach ($included in @('hic_backend', 'hic_isapi', 'faces', '.env-if-present')) {
            if ($included -notin $manifest.includes) { return $null }
        }
        foreach ($dumpName in @('hic_backend.dump', 'hic_isapi.dump')) {
            $dumpPath = Join-Path $candidate $dumpName
            if (-not (Test-Path -LiteralPath $dumpPath -PathType Leaf)) { return $null }
            $stream = [System.IO.File]::OpenRead($dumpPath)
            try {
                $header = New-Object byte[] 5
                if ($stream.Read($header, 0, 5) -ne 5 -or
                    [System.Text.Encoding]::ASCII.GetString($header) -ne 'PGDMP') { return $null }
            } finally { $stream.Dispose() }
        }
        $manifestBytes = (Get-Item -LiteralPath (Join-Path $candidate 'manifest.json')).Length
        if ($manifest.sizeBytes -le 0 -or
            (Get-DirectoryBytes -Path $candidate) - $manifestBytes -ne [long]$manifest.sizeBytes) { return $null }
        return $manifest
    } catch {
        return $null
    }
}

function Get-DockerContainerState {
    param([string]$Container, [string]$Format)

    # Windows PowerShell 5.1 treats native stderr as an error even with 2>$null.
    # Scope Continue to this read-only probe; backup operations must still fail on errors.
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker inspect --format $Format $Container 2>$null
        if ($LASTEXITCODE -ne 0) { return '' }
        return ([string]($output -join '')).Trim()
    } catch {
        return ''
    }
}

function Remove-TemporaryDatabaseDumps {
    param([string]$BackendDump, [string]$IsapiDump)

    # Cleanup must not replace a dump/copy error or invalidate a completed backup.
    $ErrorActionPreference = 'Continue'
    try {
        & docker exec hic_postgres rm -f $BackendDump $IsapiDump 2>$null | Out-Null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    }
}

function Wait-AttendraContainers {
    $deadline = (Get-Date).AddMinutes(10)
    do {
        $postgresHealth = Get-DockerContainerState -Container hic_postgres -Format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}'
        $backendState = Get-DockerContainerState -Container hic_backend -Format '{{.State.Status}}'
        if ($postgresHealth -eq 'healthy' -and $backendState -eq 'running') {
            return
        }
        Start-Sleep -Seconds 30
    } while ((Get-Date) -lt $deadline)

    throw 'Docker konteynerləri 10 dəqiqə ərzində hazır olmadı.'
}

$hostBackupLock = $null
try {
    $hostBackupLock = [IO.File]::Open((Join-Path $runtimeDirectory 'backup-host.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    if (-not (Test-Path -LiteralPath $settingsFile -PathType Leaf)) {
        throw 'Backup parametrləri tapılmadı. Əvvəl proqramın Backup bölməsində qovluğu yadda saxlayın.'
    }

    $configuration = Get-Content -LiteralPath $settingsFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $configuration.enabled -and -not $Required) {
        exit 0
    }

    $backupRoot = Get-SafeBackupRoot -ConfiguredPath ([string]$configuration.folderPath)
    New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null

    $previousStatus = Read-PreviousStatus
    $todayPrefix = (Get-Date).ToString('yyyyMMdd-')
    $todayBackup = Get-ChildItem -LiteralPath $backupRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match ('^' + [regex]::Escape($todayPrefix) + '\d{6}$') -and
            (Get-CompletedBackup -Path $_.FullName -BackupRoot $backupRoot) } |
        Select-Object -First 1
    if ($todayBackup -and -not $Force) {
        $todayManifest = Get-CompletedBackup -Path $todayBackup.FullName -BackupRoot $backupRoot
        Write-BackupStatus -Status 'SKIPPED' `
            -Message 'Bu gün üçün uğurlu backup artıq mövcuddur.' `
            -LastBackupAt $todayManifest.createdAt `
            -LastBackupBytes (Get-DirectoryBytes -Path $todayBackup.FullName) `
            -TotalBackupBytes (Get-DirectoryBytes -Path $backupRoot)
        exit 0
    }

    $nativeFile = Join-Path $resolvedProjectRoot 'native.json'
    $native = $null
    if (Test-Path -LiteralPath $nativeFile) {
        $native = Get-Content -LiteralPath $nativeFile -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    if (-not $native -and -not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker əmri tapılmadı.'
    }

    Write-BackupStatus -Status 'RUNNING' -Message 'Backup hazırlanır.' `
        -LastBackupAt $previousStatus.lastBackupAt `
        -LastBackupBytes ([long]$previousStatus.lastBackupBytes) `
        -TotalBackupBytes (Get-DirectoryBytes -Path $backupRoot)

    if (-not $native) { Wait-AttendraContainers }

    $backupTime = Get-Date
    do {
        $timestamp = $backupTime.ToString('yyyyMMdd-HHmmss')
        $incompleteDirectory = Join-Path $backupRoot "$timestamp.incomplete"
        $completedDirectory = Join-Path $backupRoot $timestamp
        $backupTime = $backupTime.AddSeconds(1)
    } while ((Test-Path -LiteralPath $incompleteDirectory) -or (Test-Path -LiteralPath $completedDirectory))
    New-Item -ItemType Directory -Path $incompleteDirectory | Out-Null
    if ($native) {
        $ownerSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls.exe $incompleteDirectory /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-19:(OI)(CI)M' "*$($ownerSid):(OI)(CI)F" | Out-Null
        if ($LASTEXITCODE) { throw 'Backup qovluğunun giriş hüquqları qoruna bilmədi.' }
    }

    $backendTempDump = "/tmp/hic_backend-$timestamp.dump"
    $isapiTempDump = "/tmp/hic_isapi-$timestamp.dump"
    try {
        if ($native) {
            $oldPassword = $env:PGPASSWORD
            try {
                $env:PGPASSWORD = $native.databasePassword
                foreach ($db in @('hic_backend', 'hic_isapi')) {
                    & (Join-Path $native.installRoot 'postgres\bin\pg_dump.exe') -h 127.0.0.1 -p $native.databasePort -U hic_user -d $db -Fc -f (Join-Path $incompleteDirectory "$db.dump")
                    if ($LASTEXITCODE) { throw 'Windows PostgreSQL backup alınmadı.' }
                }
            } finally { $env:PGPASSWORD = $oldPassword }
            Copy-Item -LiteralPath (Join-Path $resolvedProjectRoot 'faces') -Destination (Join-Path $incompleteDirectory 'faces') -Recurse
            Copy-Item -LiteralPath $nativeFile -Destination (Join-Path $incompleteDirectory '.env.backup')
        } else {
        & docker exec hic_postgres pg_dump -U hic_user -d hic_backend -Fc -f $backendTempDump
        if ($LASTEXITCODE -ne 0) { throw 'Əsas bazanın backup-u alınmadı.' }
        & docker cp "hic_postgres:$backendTempDump" (Join-Path $incompleteDirectory 'hic_backend.dump')
        if ($LASTEXITCODE -ne 0) { throw 'Əsas bazanın backup faylı kopyalanmadı.' }

        & docker exec hic_postgres pg_dump -U hic_user -d hic_isapi -Fc -f $isapiTempDump
        if ($LASTEXITCODE -ne 0) { throw 'ISAPI bazasının backup-u alınmadı.' }
        & docker cp "hic_postgres:$isapiTempDump" (Join-Path $incompleteDirectory 'hic_isapi.dump')
        if ($LASTEXITCODE -ne 0) { throw 'ISAPI bazasının backup faylı kopyalanmadı.' }

        & docker cp 'hic_backend:/app/uploads/faces' (Join-Path $incompleteDirectory 'faces')
        if ($LASTEXITCODE -ne 0) { throw 'Üz şəkilləri kopyalanmadı.' }

        $environmentFile = Join-Path $resolvedProjectRoot '.env'
        if (Test-Path -LiteralPath $environmentFile -PathType Leaf) {
            Copy-Item -LiteralPath $environmentFile -Destination (Join-Path $incompleteDirectory '.env.backup')
        }

        }

        foreach ($dumpName in @('hic_backend.dump', 'hic_isapi.dump')) {
            $dumpPath = Join-Path $incompleteDirectory $dumpName
            if (-not (Test-Path -LiteralPath $dumpPath -PathType Leaf) -or (Get-Item -LiteralPath $dumpPath).Length -le 0) {
                throw "$dumpName boşdur və backup etibarlı deyil."
            }
        }

        $backupBytes = Get-DirectoryBytes -Path $incompleteDirectory
        $manifest = [ordered]@{
            createdAt = [DateTimeOffset]::Now.ToString('o')
            status = 'SUCCESS'
            sizeBytes = $backupBytes
            retentionDays = 183
            includes = @('hic_backend', 'hic_isapi', 'faces', '.env-if-present')
        } | ConvertTo-Json
        [System.IO.File]::WriteAllText(
            (Join-Path $incompleteDirectory 'manifest.json'), $manifest, $utf8WithoutBom)
        foreach ($movePath in @($incompleteDirectory, $completedDirectory)) {
            if ([System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($movePath)) -ne $backupRoot) {
                throw 'Backup qovluğu seçilmiş kök qovluqdan kənara çıxa bilməz.'
            }
        }
        # Directory.Move fails if the destination exists instead of nesting/overwriting it.
        [System.IO.Directory]::Move($incompleteDirectory, $completedDirectory)
    } finally {
        $cleanupSucceeded = $true
        if (-not $native) { $cleanupSucceeded = Remove-TemporaryDatabaseDumps -BackendDump $backendTempDump -IsapiDump $isapiTempDump }
    }

    if (-not (Get-CompletedBackup -Path $completedDirectory -BackupRoot $backupRoot)) {
        throw 'Yeni backup struktur yoxlamasından keçmədi. Əvvəlki backup-lar qorunur.'
    }

    $cutoff = (Get-Date).Date.AddDays(-183)
    $rootPrefix = $backupRoot.TrimEnd('\') + '\'
    Get-ChildItem -LiteralPath $backupRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d{8}-\d{6}$' } |
        ForEach-Object {
            $parsedDate = [DateTime]::MinValue
            if ([DateTime]::TryParseExact(
                    $_.Name, 'yyyyMMdd-HHmmss',
                    [System.Globalization.CultureInfo]::InvariantCulture,
                    [System.Globalization.DateTimeStyles]::None,
                    [ref]$parsedDate) -and $parsedDate -lt $cutoff) {
                $candidate = [System.IO.Path]::GetFullPath($_.FullName)
                if ($candidate.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
                    (Get-CompletedBackup -Path $candidate -BackupRoot $backupRoot)) {
                    Remove-Item -LiteralPath $candidate -Recurse -Force
                }
            }
        }

    $successMessage = 'Backup uğurla tamamlandı.'
    if (-not $cleanupSucceeded) {
        $successMessage += ' Xəbərdarlıq: konteynerdəki müvəqqəti dump faylları silinmədi.'
    }
    Write-BackupStatus -Status 'SUCCESS' -Message $successMessage `
        -LastBackupAt ([DateTimeOffset]::Now.ToString('o')) `
        -LastBackupBytes (Get-DirectoryBytes -Path $completedDirectory) `
        -TotalBackupBytes (Get-DirectoryBytes -Path $backupRoot)
} catch {
    $previousStatus = Read-PreviousStatus
    Write-BackupStatus -Status 'ERROR' -Message $_.Exception.Message `
        -LastBackupAt $previousStatus.lastBackupAt `
        -LastBackupBytes ([long]$previousStatus.lastBackupBytes) `
        -TotalBackupBytes ([long]$previousStatus.totalBackupBytes)
    throw
} finally {
    if ($hostBackupLock) { $hostBackupLock.Dispose() }
}
