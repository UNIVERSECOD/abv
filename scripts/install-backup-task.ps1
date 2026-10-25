[CmdletBinding()]
param(
    [string]$ProjectRoot,
    [string]$TaskName = 'Attendra Daily Backup',
    [string]$FolderPickerTaskName = 'Attendra Backup Folder Picker'
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
$principal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent())
$runLevel = if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { 'Highest' } else { 'Limited' }

$resolvedProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$backupScript = Join-Path $resolvedProjectRoot 'scripts\backup-attendra.ps1'
if (-not (Test-Path -LiteralPath $backupScript -PathType Leaf)) {
    throw "Backup skripti tapılmadı: $backupScript"
}
$folderPickerScript = Join-Path $resolvedProjectRoot 'scripts\backup-folder-picker.ps1'
if (-not (Test-Path -LiteralPath $folderPickerScript -PathType Leaf)) {
    throw "Backup qovluq seçici skripti tapılmadı: $folderPickerScript"
}

$runtimeDirectory = Join-Path $resolvedProjectRoot 'runtime'
$settingsFile = Join-Path $runtimeDirectory 'backup-settings.json'
New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
if (-not (Test-Path -LiteralPath $settingsFile -PathType Leaf)) {
    $defaultSettings = [ordered]@{
        enabled = $true
        folderPath = 'C:\AttendraBackups\daily'
        retentionDays = 183
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText(
        $settingsFile, $defaultSettings, (New-Object System.Text.UTF8Encoding($false)))
}

$arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$backupScript`" -ProjectRoot `"$resolvedProjectRoot`""
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
$logonTrigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$logonTrigger.Delay = 'PT3M'
$dailyTrigger = New-ScheduledTaskTrigger -Daily -At '04:00'
$taskPrincipal = New-ScheduledTaskPrincipal `
    -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
    -LogonType Interactive `
    -RunLevel $runLevel
$taskSettings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 1)

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger @($logonTrigger, $dailyTrigger) `
    -Principal $taskPrincipal `
    -Settings $taskSettings `
    -Description 'Attendra bazaları, üz şəkilləri və konfiqurasiyası üçün gündəlik backup.' `
    -Force | Out-Null

$existingPickerTask = Get-ScheduledTask -TaskName $FolderPickerTaskName -ErrorAction SilentlyContinue
if ($existingPickerTask -and $existingPickerTask.State -eq 'Running') {
    Stop-ScheduledTask -TaskName $FolderPickerTaskName
    $stopDeadline = (Get-Date).AddSeconds(15)
    while ((Get-ScheduledTask -TaskName $FolderPickerTaskName).State -eq 'Running') {
        if ((Get-Date) -ge $stopDeadline) {
            throw 'Əvvəlki qovluq seçicisi dayandırılmadı. Bir qədər sonra quraşdırmanı təkrarlayın.'
        }
        Start-Sleep -Milliseconds 200
    }
}

$pickerArguments = "-NoProfile -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$folderPickerScript`""
$pickerAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $pickerArguments
$pickerTrigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$pickerSettings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero)

Register-ScheduledTask `
    -TaskName $FolderPickerTaskName `
    -Action $pickerAction `
    -Trigger $pickerTrigger `
    -Principal $taskPrincipal `
    -Settings $pickerSettings `
    -Description 'Attendra Backup səhifəsi üçün lokal Windows qovluq seçicisi.' `
    -Force | Out-Null

Start-ScheduledTask -TaskName $FolderPickerTaskName
& (Join-Path $PSScriptRoot 'install-backup-worker.ps1') -ProjectRoot $resolvedProjectRoot

# Task launch is asynchronous. Verify the helper, not just task registration.
$pickerReady = $false
$startDeadline = (Get-Date).AddSeconds(15)
do {
    try {
        $probe = Invoke-WebRequest -UseBasicParsing -Method Options `
            -Uri 'http://127.0.0.1:18765/select-folder' `
            -Headers @{ Origin = 'http://localhost:3000' } -TimeoutSec 2
        $pickerReady = $probe.StatusCode -eq 204
    } catch { }
    if (-not $pickerReady) { Start-Sleep -Milliseconds 500 }
} while (-not $pickerReady -and (Get-Date) -lt $startDeadline)
if (-not $pickerReady) {
    throw 'Backup tapşırıqları yaradıldı, amma qovluq seçicisi cavab vermir. Task Scheduler-də qovluq seçici tapşırığını yenidən başladın.'
}

Write-Host "'$TaskName' tapşırığı quraşdırıldı. İlk backup növbəti girişdən 3 dəqiqə sonra və ya saat 04:00-da işləyəcək."
Write-Host "'$FolderPickerTaskName' tapşırığı quraşdırıldı və qovluq seçici başladıldı."
