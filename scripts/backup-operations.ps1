# Host-side implementation. Never dot-source an uploaded file or execute a command from an archive.
Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

function Write-OperationJson($Path, $Value) {
    $temporary = "$Path.tmp"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Set-OperationStatus($Job, $State, $Message, $Preview = $null) {
    Write-OperationJson (Join-Path $Job 'status.json') @{ state = $State; message = $Message; preview = $Preview }
}

function Read-EnvironmentFile($Path) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$') {
            $value = $Matches[2]
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $values[$Matches[1]] = $value
        }
    }
    return $values
}

function Initialize-BackupHost($ProjectRoot) {
    $script:BackupRoot = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\')
    $script:OperationsRoot = Join-Path $script:BackupRoot 'runtime\backup-operations'
    New-Item -ItemType Directory -Path $script:OperationsRoot -Force | Out-Null
    if ((Get-Item -LiteralPath $script:OperationsRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Backup iş qovluğu keçid ola bilməz' }
    $script:NativePath = Join-Path $script:BackupRoot 'native.json'
    $script:IsNative = Test-Path -LiteralPath $script:NativePath
    if ($script:IsNative) {
        $script:Native = Get-Content -LiteralPath $script:NativePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $script:Mode = 'native'
        $script:Faces = Join-Path $script:BackupRoot 'faces'
    } else {
        $script:Mode = 'docker'
        $script:Compose = @('compose', '--project-directory', $script:BackupRoot, '-f', (Join-Path $script:BackupRoot 'docker-compose.yml'))
        $override = Join-Path $script:BackupRoot 'runtime\docker-local-ports.yml'
        if (Test-Path -LiteralPath $override) { $script:Compose += @('-f', $override) }
    }
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $grants = @('*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F', "*$($owner):(OI)(CI)F")
    if ($script:IsNative) { $grants += '*S-1-5-19:(OI)(CI)M' }
    & icacls.exe $script:OperationsRoot /inheritance:r /grant:r @grants > $null 2>&1
    if ($LASTEXITCODE) { throw 'Backup iş qovluğunun giriş hüquqları qoruna bilmədi' }
}

function Save-OperationArchive($Job, $Name) {
    $settings = Join-Path $script:BackupRoot 'runtime\backup-settings.json'
    $destinationRoot = Join-Path $script:BackupRoot 'backups'
    if (Test-Path -LiteralPath $settings) {
        $destinationRoot = [string](Get-Content $settings -Raw -Encoding UTF8 | ConvertFrom-Json).folderPath
    }
    if (![IO.Path]::IsPathRooted($destinationRoot) -or $destinationRoot.Contains('..')) { throw 'Backup qovluğu düzgün deyil' }
    $destinationRoot = [IO.Path]::GetFullPath($destinationRoot).TrimEnd('\')
    if ($destinationRoot -eq [IO.Path]::GetPathRoot($destinationRoot).TrimEnd('\')) { throw 'Disk kökü backup qovluğu ola bilməz' }
    New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null
    $id = [IO.Path]::GetFileName($Job)
    $privateDirectory = Join-Path $destinationRoot "attendra-operation-$id"
    New-Item -ItemType Directory -Path $privateDirectory | Out-Null
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $privateDirectory /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' "*$($owner):(OI)(CI)F" > $null 2>&1
    if ($LASTEXITCODE) { throw 'Backup qovluğunun giriş hüquqları qoruna bilmədi' }
    $destinationRoot = $privateDirectory
    $destination = Join-Path $destinationRoot ("attendra-$($Name.Replace('.zip', ''))-$id.zip")
    [IO.File]::Copy((Join-Path $Job $Name), $destination, $false)
    & icacls.exe $destination /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' "*$($owner):F" > $null 2>&1
    if ($LASTEXITCODE) { throw 'Backup faylının giriş hüquqları qoruna bilmədi' }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath (Join-Path $Job $Name) -Algorithm SHA256).Hash) { throw 'Saxlanılmış backupun yoxlaması keçmədi' }
}

function Invoke-Compose {
    param([string[]]$Arguments)
    # Windows PowerShell 5.1 wraps normal native stderr progress as ErrorRecord.
    # Suppress progress and rely on the command's exit code, never on its output stream.
    $ErrorActionPreference = 'Continue'
    & docker @script:Compose @Arguments > $null 2>&1
    if ($LASTEXITCODE) { throw 'Docker əməliyyatı uğursuz oldu' }
}

function Invoke-BackupSql([string]$Sql, [string]$Database = 'postgres') {
    if ($script:IsNative) {
        $previous = $env:PGPASSWORD
        try {
            $env:PGPASSWORD = $script:Native.databasePassword
            $result = $Sql | & (Join-Path $script:Native.installRoot 'postgres\bin\psql.exe') -h 127.0.0.1 -p $script:Native.databasePort -U hic_user -d $Database -v ON_ERROR_STOP=1 -At 2>$null
            if ($LASTEXITCODE) { throw 'PostgreSQL əməliyyatı uğursuz oldu' }
            return $result
        } finally { $env:PGPASSWORD = $previous }
    } else {
        $result = $Sql | & docker exec -i hic_postgres psql -U hic_user -d $Database -v ON_ERROR_STOP=1 -At 2>$null
        if ($LASTEXITCODE) { throw 'PostgreSQL əməliyyatı uğursuz oldu' }
        return $result
    }
}

function Stop-BackupApplications {
    if ($script:IsNative) {
        foreach ($name in @('AttendraBackend', 'AttendraIsapi')) {
            Stop-Service -Name $name -ErrorAction Stop
            (Get-Service $name).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
        }
    } else {
        $images = @{}
        foreach ($name in @('backend', 'isapi')) {
            $image = & docker inspect "hic_$name" --format '{{.Image}}' 2>$null
            if ($LASTEXITCODE -or $image -notmatch '^sha256:[a-f0-9]{64}$') { throw 'İşləyən tətbiqin image-i müəyyən edilə bilmədi' }
            $images[$name] = @{ image = [string]$image; pull_policy = 'never' }
        }
        Write-OperationJson (Join-Path $script:OperationsRoot 'installed-images.json') @{ services = $images }
        Invoke-Compose -Arguments @('stop', 'backend', 'isapi')
    }
}

function Start-BackupApplications([switch]$Reconfigure) {
    if ($script:IsNative) {
        foreach ($name in @('AttendraIsapi', 'AttendraBackend')) { Start-Service -Name $name -ErrorAction Stop }
    } else {
        if ($Reconfigure) {
            Invoke-Compose -Arguments @('-f', (Join-Path $script:OperationsRoot 'installed-images.json'), 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', 'isapi', 'backend')
        } else {
            & docker start hic_isapi hic_backend > $null 2>&1
            if ($LASTEXITCODE) { throw 'Tətbiq konteynerləri başladılmadı' }
        }
        Invoke-Compose -Arguments @('exec', '-T', 'frontend', 'nginx', '-s', 'reload')
    }
}

function Wait-BackupHealth {
    if ($script:IsNative) { $port = $script:Native.backendPort; $isapiPort = $script:Native.isapiPort; $key = $script:Native.apiKey }
    else {
        $mappings = @(& docker port hic_backend 8080/tcp 2>$null)
        if ($LASTEXITCODE) { throw 'Backend portu tapılmadı' }
        $mapped = [string]($mappings | Select-Object -First 1)
        if ($mapped -notmatch ':(\d+)$') { throw 'Backend portu tapılmadı' }
        $port = $Matches[1]
        $isapiMappings = @(& docker port hic_isapi 8081/tcp 2>$null)
        if ($LASTEXITCODE) { throw 'ISAPI portu tapılmadı' }
        $mappedIsapi = [string]($isapiMappings | Select-Object -First 1)
        if ($mappedIsapi -notmatch ':(\d+)$') { throw 'ISAPI portu tapılmadı' }
        $isapiPort = $Matches[1]
        $key = & docker exec hic_isapi printenv ISAPI_API_KEY 2>$null
        if ($LASTEXITCODE -or !$key) { throw 'ISAPI konfiqurasiyası oxuna bilmədi' }
    }
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                $bridge = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$isapiPort/api/devices" -Headers @{ 'X-API-Key' = $key } -TimeoutSec 5
                if ($bridge.StatusCode -eq 200) { return }
            }
        } catch { }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Bərpadan sonra backend açılmadı'
}

function Export-BackupDatabase($Database, $Destination) {
    if ($script:IsNative) {
        $previous = $env:PGPASSWORD
        try {
            $env:PGPASSWORD = $script:Native.databasePassword
            & (Join-Path $script:Native.installRoot 'postgres\bin\pg_dump.exe') -h 127.0.0.1 -p $script:Native.databasePort -U hic_user -d $Database -Fc -f $Destination > $null 2>&1
            if ($LASTEXITCODE) { throw 'Bazanın backupu alınmadı' }
        } finally { $env:PGPASSWORD = $previous }
    } else {
        $remote = "/tmp/attendra-$([Guid]::NewGuid().ToString('N')).dump"
        try {
            & docker exec hic_postgres pg_dump -U hic_user -d $Database -Fc -f $remote > $null 2>&1
            if ($LASTEXITCODE) { throw 'Bazanın backupu alınmadı' }
            & docker cp "hic_postgres:$remote" $Destination > $null 2>&1
            if ($LASTEXITCODE) { throw 'Backup kopyalanmadı' }
        } finally { & docker exec hic_postgres rm -f $remote > $null 2>&1 }
    }
}

function Copy-BackupFaces($Destination) {
    if ($script:IsNative) { Copy-Item -LiteralPath $script:Faces -Destination $Destination -Recurse }
    else {
        & docker cp 'hic_backend:/app/uploads/faces' $Destination > $null 2>&1
        if ($LASTEXITCODE) { throw 'Foto backupu alınmadı' }
    }
}

function New-BackupSnapshot($Folder, $Archive) {
    New-Item -ItemType Directory -Path $Folder | Out-Null
    Export-BackupDatabase 'hic_backend' (Join-Path $Folder 'hic_backend.dump')
    Export-BackupDatabase 'hic_isapi' (Join-Path $Folder 'hic_isapi.dump')
    Copy-BackupFaces (Join-Path $Folder 'faces')
    $configuration = if ($script:IsNative) { $script:NativePath } else { Join-Path $script:BackupRoot '.env' }
    Copy-Item -LiteralPath $configuration -Destination (Join-Path $Folder '.env.backup')
    New-Item -ItemType Directory -Path (Join-Path $Folder 'configs') | Out-Null
    foreach ($relative in @('runtime\backup-settings.json', 'runtime\docker-local-ports.yml', 'docker-compose.yml', 'Caddyfile')) {
        $source = Join-Path $script:BackupRoot $relative
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $Folder ('configs\' + [IO.Path]::GetFileName($source))) }
    }
    $hashes = @{}
    foreach ($file in Get-ChildItem -LiteralPath $Folder -Recurse -File -Force) {
        if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Backupda simvolik keçid ola bilməz' }
        $relative = $file.FullName.Substring($Folder.Length + 1).Replace('\', '/')
        $hashes[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $version = @(Invoke-BackupSql "SELECT version FROM flyway_schema_history_backend WHERE success ORDER BY installed_rank DESC LIMIT 1;" 'hic_backend')[0]
    Write-OperationJson (Join-Path $Folder 'manifest.json') @{
        format = 2; status = 'SUCCESS'; mode = $script:Mode; createdAt = [DateTimeOffset]::Now.ToString('o')
        schemaVersion = $version; hashes = $hashes; includes = @('hic_backend', 'hic_isapi', 'faces', 'configuration')
    }
    $zip = [IO.Compression.ZipFile]::Open($Archive, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in Get-ChildItem -LiteralPath $Folder -Recurse -File -Force) {
            $name = $file.FullName.Substring($Folder.Length + 1).Replace('\', '/')
            $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $name, [IO.Compression.CompressionLevel]::Optimal)
        }
    } finally { $zip.Dispose() }
}

function Expand-VerifiedBackup($Archive, $Destination) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        if ($zip.Entries.Count -gt 100000) { throw 'Arxivdə həddən çox fayl var' }
        $manifestEntries = @($zip.Entries | Where-Object { $_.FullName.Replace('\', '/') -match '(^|/)manifest\.json$' })
        if ($manifestEntries.Count -ne 1) { throw 'Backup manifesti tapılmadı' }
        $manifestName = $manifestEntries[0].FullName.Replace('\', '/')
        $prefix = $manifestName.Substring(0, $manifestName.Length - 'manifest.json'.Length)
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $total = [long]0
        New-Item -ItemType Directory -Path $Destination | Out-Null
        foreach ($entry in $zip.Entries) {
            $entryName = $entry.FullName.Replace('\', '/')
            if (!$entryName.StartsWith($prefix, [StringComparison]::Ordinal)) { throw 'Arxiv strukturu düzgün deyil' }
            $name = $entryName.Substring($prefix.Length)
            if (!$name) { continue }
            if ($name.Contains('\') -or $name.StartsWith('/') -or $name.Contains(':') -or $name.Split('/') -contains '..' -or $name.Split('/') -contains '.') { throw 'Arxivdə təhlükəli fayl yolu var' }
            foreach ($part in $name.TrimEnd('/').Split('/')) {
                if (!$part -or $part -match '[\x00-\x1f<>"|?*]' -or $part.EndsWith('.') -or $part.EndsWith(' ') -or $part -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)') { throw 'Arxivdə Windows üçün etibarsız fayl adı var' }
            }
            if ((($entry.ExternalAttributes -shr 16) -band 61440) -eq 40960 -or ($entry.ExternalAttributes -band 1024)) { throw 'Arxivdə simvolik keçid var' }
            if ($name.EndsWith('/')) {
                if ($name -ne 'faces/' -and !$name.StartsWith('faces/') -and $name -ne 'configs/') { throw 'Naməlum backup qovluğu' }
                continue
            }
            if (!$seen.Add($name)) { throw 'Arxivdə təkrar fayl yolu var' }
            if ($name -notin @('manifest.json', 'hic_backend.dump', 'hic_isapi.dump', '.env.backup', 'configs/backup-settings.json', 'configs/docker-local-ports.yml', 'configs/docker-compose.yml', 'configs/Caddyfile') -and !$name.StartsWith('faces/')) { throw 'Arxivdə naməlum fayl var' }
            $total += $entry.Length
            if ($total -gt 20GB -or $entry.Length -gt 10GB) { throw 'Açılmış arxiv həddən böyükdür' }
            $target = Join-Path $Destination $name.Replace('/', '\')
            $parent = [IO.Path]::GetDirectoryName($target)
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
            $inputStream = $entry.Open()
            $outputStream = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
            try {
                $buffer = New-Object byte[] 65536
                $written = [long]0
                while (($count = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $written += $count
                    if ($written -gt $entry.Length -or $written -gt 10GB) { throw 'Arxivin açılmış ölçüsü düzgün deyil' }
                    $outputStream.Write($buffer, 0, $count)
                }
                if ($written -ne $entry.Length) { throw 'Arxiv natamamdır' }
            } finally { $inputStream.Dispose(); $outputStream.Dispose() }
        }
        foreach ($required in @('hic_backend.dump', 'hic_isapi.dump', '.env.backup', 'manifest.json')) {
            if (!$seen.Contains($required)) { throw "Backup natamamdır: $required" }
        }
        $manifest = Get-Content -LiteralPath (Join-Path $Destination 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($manifest.status -ne 'SUCCESS') { throw 'Backup uğurla tamamlanmayıb' }
        foreach ($required in @('hic_backend', 'hic_isapi', 'faces')) {
            if (!$manifest.PSObject.Properties['includes'] -or $required -notin $manifest.includes) { throw 'Backupun tərkibi natamamdır' }
        }
        if ($manifest.PSObject.Properties['format'] -and $manifest.format -notin @(1, 2)) { throw 'Backup formatı dəstəklənmir' }
        foreach ($dump in @('hic_backend.dump', 'hic_isapi.dump')) {
            $stream = [IO.File]::OpenRead((Join-Path $Destination $dump))
            try { $magic = New-Object byte[] 5; if ($stream.Read($magic, 0, 5) -ne 5 -or [Text.Encoding]::ASCII.GetString($magic) -ne 'PGDMP') { throw 'PostgreSQL backupu düzgün deyil' } }
            finally { $stream.Dispose() }
        }
        if ($manifest.PSObject.Properties['format'] -and $manifest.format -eq 2) {
            if ($manifest.mode -ne $script:Mode) { throw 'Docker və Windows backup formatlarını qarışdırmaq olmaz' }
            $actualFiles = @($seen | Where-Object { $_ -ne 'manifest.json' })
            if (@($manifest.hashes.PSObject.Properties).Count -ne $actualFiles.Count) { throw 'Backup fayl siyahısı uyğun deyil' }
            foreach ($name in $actualFiles) {
                $expected = $manifest.hashes.PSObject.Properties[$name]
                if (!$expected -or (Get-FileHash -LiteralPath (Join-Path $Destination $name) -Algorithm SHA256).Hash -ne $expected.Value) { throw 'Backupun bütövlük yoxlaması keçmədi' }
            }
        } else {
            # Legacy daily backup: same fixed files, validated dumps and configuration, no arbitrary SQL files.
            if ($script:IsNative) { $null = Get-Content (Join-Path $Destination '.env.backup') -Raw -Encoding UTF8 | ConvertFrom-Json }
            elseif (!(Read-EnvironmentFile (Join-Path $Destination '.env.backup')).ContainsKey('ENCRYPTION_SECRET_KEY')) { throw 'Şifrələmə konfiqurasiyası yoxdur' }
        }
        New-Item -ItemType Directory -Path (Join-Path $Destination 'faces') -Force | Out-Null
        return @{ createdAt = [string]$manifest.createdAt; mode = $script:Mode; files = $seen.Count; bytes = $total; configurationIncluded = $true }
    } finally { $zip.Dispose() }
}

function Import-StagedDatabase($Database, $Dump) {
    if ($Database -notmatch '^restore_[a-f0-9]{12}_(backend|isapi)$') { throw 'Test bazasının adı düzgün deyil' }
    $null = Invoke-BackupSql "CREATE DATABASE $Database OWNER hic_user;"
    if ($script:IsNative) {
        $previous = $env:PGPASSWORD
        try {
            $env:PGPASSWORD = $script:Native.databasePassword
            & (Join-Path $script:Native.installRoot 'postgres\bin\pg_restore.exe') -h 127.0.0.1 -p $script:Native.databasePort -U hic_user --no-owner --no-privileges --exit-on-error -d $Database $Dump > $null 2>&1
            if ($LASTEXITCODE) { throw 'Backup test bazasına bərpa edilə bilmədi' }
        } finally { $env:PGPASSWORD = $previous }
    } else {
        $remote = "/tmp/$Database.dump"
        try {
            & docker cp $Dump "hic_postgres:$remote" > $null 2>&1
            if ($LASTEXITCODE) { throw 'Backup test bazasına kopyalanmadı' }
            & docker exec hic_postgres pg_restore -U hic_user --no-owner --no-privileges --exit-on-error -d $Database $remote > $null 2>&1
            if ($LASTEXITCODE) { throw 'Backup test bazasına bərpa edilə bilmədi' }
        } finally { & docker exec hic_postgres rm -f $remote > $null 2>&1 }
    }
}

function Rename-BackupDatabase($From, $To) {
    foreach ($name in @($From, $To)) {
        if ($name -notmatch '^(hic_(backend|isapi)|(restore|before|failed)_[a-f0-9]{12}_(backend|isapi))$') { throw 'Baza adı düzgün deyil' }
    }
    $null = Invoke-BackupSql "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$From' AND pid <> pg_backend_pid();"
    $null = Invoke-BackupSql "ALTER DATABASE $From RENAME TO $To;"
}

function Test-BackupDatabase($Name) {
    if ($Name -notmatch '^[a-z0-9_]+$') { throw 'Baza adı düzgün deyil' }
    return @(Invoke-BackupSql "SELECT 1 FROM pg_database WHERE datname='$Name';").Count -gt 0
}

function Install-BackupConfiguration($Source, [switch]$Exact) {
    if ($script:IsNative) {
        $restored = Get-Content -LiteralPath (Join-Path $Source '.env.backup') -Raw -Encoding UTF8 | ConvertFrom-Json
        if (!$Exact) {
            foreach ($field in @('installRoot', 'dataRoot', 'webPort', 'databasePort', 'backendPort', 'isapiPort', 'databasePassword')) {
                $restored.$field = $script:Native.$field
            }
        }
        foreach ($field in @('jwtSecret', 'encryptionKey', 'apiKey')) {
            if (!$restored.PSObject.Properties[$field] -or !$restored.$field) { throw 'Backup konfiqurasiyası natamamdır' }
        }
        Write-OperationJson $script:NativePath $restored
        $script:Native = $restored
    } else {
        $path = Join-Path $script:BackupRoot '.env'
        $restored = Read-EnvironmentFile (Join-Path $Source '.env.backup')
        foreach ($key in @('JWT_SECRET', 'ENCRYPTION_SECRET_KEY', 'ISAPI_API_KEY')) {
            if (!$restored.ContainsKey($key) -or !$restored[$key]) { throw 'Backup konfiqurasiyası natamamdır' }
        }
        if ($Exact) { Copy-Item -LiteralPath (Join-Path $Source '.env.backup') -Destination $path -Force }
        else {
            $lines = [IO.File]::ReadAllLines((Join-Path $Source '.env.backup'))
            $hostKeys = '^\s*(POSTGRES_PASSWORD|CORS_ALLOWED_ORIGINS|COMPUTER_IP|COMPOSE_PROJECT_NAME)\s*='
            $hostLines = @([IO.File]::ReadAllLines($path) | Where-Object { $_ -match $hostKeys })
            $result = @($lines | Where-Object { $_ -notmatch $hostKeys }) + $hostLines
            [IO.File]::WriteAllLines($path, $result, [Text.UTF8Encoding]::new($false))
        }
    }
    $settings = Join-Path $Source 'configs\backup-settings.json'
    if (Test-Path -LiteralPath $settings) {
        # Keep this host's backup destination, but restore the scheduling preference.
        $saved = Get-Content $settings -Raw -Encoding UTF8 | ConvertFrom-Json
        $target = Join-Path $script:BackupRoot 'runtime\backup-settings.json'
        if (!$Exact -and (Test-Path $target)) {
            $current = Get-Content $target -Raw -Encoding UTF8 | ConvertFrom-Json
            $saved.folderPath = $current.folderPath
        }
        Write-OperationJson $target $saved
    }
}

function Install-BackupFaces($Source) {
    if ($script:IsNative) { $target = $script:Faces }
    else {
        # Resolve the existing volume; never create/delete a customer volume.
        $mounts = & docker inspect hic_backend --format '{{json .Mounts}}' 2>$null | ConvertFrom-Json
        if ($LASTEXITCODE) { throw 'Foto volume tapılmadı' }
        $mount = @($mounts | Where-Object Destination -eq '/app/uploads/faces')
        if ($mount.Count -ne 1 -or $mount[0].Type -ne 'volume') { throw 'Foto volume konfiqurasiyası dəstəklənmir' }
        # Copy into the stopped application's existing volume with a small trusted local helper.
        $image = & docker inspect hic_backend --format '{{.Config.Image}}'
        & docker run --rm --network none --mount "type=volume,source=$($mount[0].Name),target=/faces" --mount "type=bind,source=$Source,target=/incoming,readonly" --entrypoint sh $image -c 'find /faces -mindepth 1 -maxdepth 1 -exec rm -rf {} + && cp -a /incoming/. /faces/' > $null 2>&1
        if ($LASTEXITCODE) { throw 'Fotolar bərpa edilə bilmədi' }
        return
    }
    # Atomic directory exchange keeps the previous photos until the operation is complete.
    $old = "$target.previous-$([Guid]::NewGuid().ToString('N'))"
    if (Test-Path -LiteralPath $target) { Move-Item -LiteralPath $target -Destination $old }
    Copy-Item -LiteralPath $Source -Destination $target -Recurse
}

function Undo-BackupRestore($Job, $Plan) {
    Stop-BackupApplications
    foreach ($suffix in @('backend', 'isapi')) {
        $old = "before_$($Plan.tag)_$suffix"
        if (Test-BackupDatabase $old) {
            if (Test-BackupDatabase "hic_$suffix") { Rename-BackupDatabase "hic_$suffix" "failed_$($Plan.tag)_$suffix" }
            Rename-BackupDatabase $old "hic_$suffix"
        }
    }
    Install-BackupConfiguration (Join-Path $Job 'safety') -Exact
    Install-BackupFaces (Join-Path $Job 'safety\faces')
    Start-BackupApplications -Reconfigure
    Wait-BackupHealth
    if (!$Plan.removalWasPaused) {
        Remove-Item -LiteralPath (Join-Path $script:BackupRoot 'runtime\device-removal-paused.json') -ErrorAction SilentlyContinue
    }
}

function Invoke-BackupOperation($Job, $Action) {
    if ($Action -eq 'INSPECT') {
        Set-OperationStatus $Job 'RUNNING' 'Backup faylı yoxlanılır'
        $preview = Expand-VerifiedBackup (Join-Path $Job 'upload.zip') (Join-Path $Job 'incoming')
        Set-OperationStatus $Job 'READY' 'Backup yoxlanıldı. Bərpa üçün təsdiq gözlənilir' $preview
        return
    }
    if ($Action -eq 'CREATE') {
        Set-OperationStatus $Job 'RUNNING' 'Uyğun snapshot üçün backend və ISAPI qısa müddətə dayandırılır'
        try {
            Write-OperationJson (Join-Path $Job 'resume-needed.json') @{ reason = 'snapshot' }
            Stop-BackupApplications
            New-BackupSnapshot (Join-Path $Job 'snapshot') (Join-Path $Job 'backup.zip')
            Save-OperationArchive $Job 'backup.zip'
        } finally { Start-BackupApplications }
        Wait-BackupHealth
        Remove-Item -LiteralPath (Join-Path $Job 'resume-needed.json')
        Set-OperationStatus $Job 'SUCCESS' 'Backup hazırdır. Bazalar, fotolar və konfiqurasiyalar daxildir'
        return
    }
    if ($Action -ne 'RESTORE') { throw 'Naməlum backup əməliyyatı' }
    $tag = [IO.Path]::GetFileName($Job).Replace('-', '').Substring(0, 12)
    $plan = @{ tag = $tag; removalWasPaused = (Test-Path (Join-Path $script:BackupRoot 'runtime\device-removal-paused.json')) }
    $incoming = Join-Path $Job 'incoming'
    Set-OperationStatus $Job 'RUNNING' 'Backup ayrıca test bazalarına bərpa edilir. Cari məlumatlar saxlanılır'
    foreach ($suffix in @('backend', 'isapi')) { Import-StagedDatabase "restore_${tag}_$suffix" (Join-Path $incoming "hic_$suffix.dump") }
    $currentVersion = [int](@(Invoke-BackupSql "SELECT version FROM flyway_schema_history_backend WHERE success ORDER BY installed_rank DESC LIMIT 1;" 'hic_backend')[0])
    $targetVersion = [int](@(Invoke-BackupSql "SELECT version FROM flyway_schema_history_backend WHERE success ORDER BY installed_rank DESC LIMIT 1;" "restore_${tag}_backend")[0])
    if ($targetVersion -gt $currentVersion) { throw 'Backup daha yeni proqram versiyasındandır' }
    # Do not resume physical-device deletion jobs from an old restored queue without operator review.
    try {
        Write-OperationJson (Join-Path $Job 'resume-needed.json') @{ reason = 'restore' }
        Stop-BackupApplications
        Set-OperationStatus $Job 'RUNNING' 'Bərpadan əvvəl cari vəziyyətin tam backupu alınır'
        New-BackupSnapshot (Join-Path $Job 'safety') (Join-Path $Job 'before-restore.zip')
        Save-OperationArchive $Job 'before-restore.zip'
        Write-OperationJson (Join-Path $Job 'restore-plan.json') $plan
        Write-OperationJson (Join-Path $script:BackupRoot 'runtime\device-removal-paused.json') @{ reason = 'backup-restore'; createdAt = [DateTimeOffset]::Now.ToString('o') }
        foreach ($suffix in @('backend', 'isapi')) {
            Rename-BackupDatabase "hic_$suffix" "before_${tag}_$suffix"
            Rename-BackupDatabase "restore_${tag}_$suffix" "hic_$suffix"
        }
        Install-BackupConfiguration $incoming
        Install-BackupFaces (Join-Path $incoming 'faces')
        Start-BackupApplications -Reconfigure
        Wait-BackupHealth
        Set-OperationStatus $Job 'SUCCESS' 'Bərpa tamamlandı. Backupdakı hesabla yenidən daxil olun. Cihaz silmə növbəsi yoxlama üçün dayandırılıb'
        Rename-Item -LiteralPath (Join-Path $Job 'restore-plan.json') -NewName 'restore-completed.json'
        Remove-Item -LiteralPath (Join-Path $Job 'resume-needed.json')
    } catch {
        if (Test-Path (Join-Path $Job 'restore-plan.json')) {
            try {
                Undo-BackupRestore $Job $plan
                Rename-Item -LiteralPath (Join-Path $Job 'restore-plan.json') -NewName 'restore-rolled-back.json'
                Remove-Item -LiteralPath (Join-Path $Job 'resume-needed.json')
                Set-OperationStatus $Job 'ERROR' 'Bərpa alınmadı. Əvvəlki vəziyyət avtomatik geri qaytarıldı'
            } catch {
                Set-OperationStatus $Job 'ERROR' 'Bərpa və avtomatik geri qaytarma tamamlanmadı. Cari backup və əvvəlki bazalar saxlanılıb; administrator müdaxiləsi lazımdır'
            }
        } else { Start-BackupApplications; throw }
    }
}
