param([string]$ProjectRoot, [switch]$Once)
$ErrorActionPreference = 'Stop'
if (!$ProjectRoot) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'backup-operations.ps1')
Initialize-BackupHost $ProjectRoot
$lock = $null
try {
    $lock = [IO.File]::Open((Join-Path $script:OperationsRoot 'worker.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
} catch { exit 0 }
try {
    # Recover an interrupted swap before accepting any new work.
    foreach ($job in Get-ChildItem -LiteralPath $script:OperationsRoot -Directory) {
        if ($job.Name -notmatch '^[a-f0-9-]{36}$' -or $job.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
        $planPath = Join-Path $job.FullName 'restore-plan.json'
        if (Test-Path -LiteralPath $planPath) {
            try {
                Undo-BackupRestore $job.FullName (Get-Content $planPath -Raw -Encoding UTF8 | ConvertFrom-Json)
                Rename-Item -LiteralPath $planPath -NewName 'restore-rolled-back.json'
                Set-OperationStatus $job.FullName 'ERROR' 'Yarımçıq bərpa aşkarlandı; əvvəlki vəziyyət geri qaytarıldı'
            } catch { Set-OperationStatus $job.FullName 'ERROR' 'Yarımçıq bərpa var. Yeni əməliyyatdan əvvəl administrator yoxlaması lazımdır'; exit 1 }
        }
        $resume = Join-Path $job.FullName 'resume-needed.json'
        if (Test-Path -LiteralPath $resume) {
            Start-BackupApplications
            Wait-BackupHealth
            Remove-Item -LiteralPath $resume
            Set-OperationStatus $job.FullName 'ERROR' 'Yarımçıq backup əməliyyatından sonra tətbiq yenidən başladıldı; backupu yenidən yaradın'
        }
        $claimed = @(Get-ChildItem -LiteralPath $job.FullName -Filter '*.claimed')
        if ($claimed.Count -gt 0 -and !(Test-Path (Join-Path $job.FullName 'restore-plan.json'))) {
            $pending = Test-Path (Join-Path $job.FullName 'restore-request.json')
            $statusPath = Join-Path $job.FullName 'status.json'
            if ((Test-Path $statusPath) -and !$pending) {
                $state = (Get-Content $statusPath -Raw -Encoding UTF8 | ConvertFrom-Json).state
                if ($state -in @('RUNNING', 'QUEUED')) { Set-OperationStatus $job.FullName 'ERROR' 'Yarımçıq əməliyyat aşkarlandı; faylı yenidən göndərin' }
            }
            $active = Join-Path $script:OperationsRoot 'restore-active.json'
            if (!$pending -and (Test-Path $active) -and (Get-Content $active -Raw).Trim() -eq $job.Name) { Remove-Item -LiteralPath $active }
        }
    }
    do {
        Write-OperationJson (Join-Path $script:OperationsRoot 'heartbeat.json') @{ updatedAt = [DateTimeOffset]::Now.ToString('o') }
        foreach ($job in Get-ChildItem -LiteralPath $script:OperationsRoot -Directory | Sort-Object CreationTimeUtc) {
            if ($job.Name -notmatch '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$' -or $job.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
            foreach ($requestName in @('request.json', 'restore-request.json')) {
                $requestPath = Join-Path $job.FullName $requestName
                if (!(Test-Path -LiteralPath $requestPath)) { continue }
                $claimed = "$requestPath.claimed"
                Move-Item -LiteralPath $requestPath -Destination $claimed
                $action = ''
                $operationLock = $null
                try {
                    $action = (Get-Content $claimed -Raw -Encoding UTF8 | ConvertFrom-Json).action
                    if ($action -in @('CREATE', 'RESTORE')) {
                        $operationLock = [IO.File]::Open((Join-Path $script:BackupRoot 'runtime\backup-host.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
                    }
                    Invoke-BackupOperation $job.FullName $action
                } catch { Set-OperationStatus $job.FullName 'ERROR' 'Backup əməliyyatı tamamlanmadı. Cari bazalar və ehtiyat nüsxələr saxlanılır. Administrator yoxlaması lazımdır' }
                finally {
                    if ($operationLock) { $operationLock.Dispose() }
                    if ($action -eq 'RESTORE' -and !(Test-Path (Join-Path $job.FullName 'restore-plan.json'))) {
                        $active = Join-Path $script:OperationsRoot 'restore-active.json'
                        if ((Test-Path $active) -and (Get-Content $active -Raw).Trim() -eq $job.Name) { Remove-Item -LiteralPath $active }
                    }
                }
                if (Test-Path (Join-Path $job.FullName 'restore-plan.json')) { exit 1 }
            }
        }
        if (!$Once) { Start-Sleep -Seconds 2 }
    } while (!$Once)
} finally { $lock.Dispose() }
