param([string]$ProjectRoot, [switch]$NativeInstallation, [switch]$Remove)
$ErrorActionPreference = 'Stop'
if (!$ProjectRoot) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\')
$worker = Join-Path $PSScriptRoot 'backup-worker.ps1'
$digest = [Security.Cryptography.SHA256]::Create()
try { $suffix = ([BitConverter]::ToString($digest.ComputeHash([Text.Encoding]::UTF8.GetBytes($ProjectRoot)))).Replace('-', '').Substring(0, 8) }
finally { $digest.Dispose() }
$name = "Attendra Backup Worker $suffix"
$startupLink = Join-Path ([Environment]::GetFolderPath('Startup')) "$name.lnk"
$existing = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
if ($existing -and $existing.State -eq 'Running') {
    $operations = Join-Path $ProjectRoot 'runtime\backup-operations'
    foreach ($status in Get-ChildItem -LiteralPath $operations -Filter 'status.json' -Recurse -ErrorAction SilentlyContinue) {
        if ((Get-Content $status.FullName -Raw -Encoding UTF8 | ConvertFrom-Json).state -eq 'RUNNING') {
            throw 'Backup əməliyyatı davam edir. Quraşdırmanı əməliyyat bitəndən sonra təkrarlayın.'
        }
    }
    Stop-ScheduledTask -TaskName $name
    Start-Sleep -Seconds 2
}
if ($Remove) {
    if ($existing) { Unregister-ScheduledTask -TaskName $name -Confirm:$false }
    if (Test-Path -LiteralPath $startupLink) { Remove-Item -LiteralPath $startupLink }
    return
}
$arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$worker`" -ProjectRoot `"$ProjectRoot`""
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
if ($NativeInstallation) {
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount
    $trigger = New-ScheduledTaskTrigger -AtStartup
} else {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $isAdmin = ([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    $level = if ($isAdmin) { 'Highest' } else { 'Limited' }
    $principal = New-ScheduledTaskPrincipal -UserId $identity.Name -LogonType Interactive -RunLevel $level
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $identity.Name
}
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
try {
    Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
    Start-ScheduledTask -TaskName $name
    if (Test-Path -LiteralPath $startupLink) { Remove-Item -LiteralPath $startupLink }
    Write-Output 'Backup worker quraşdırıldı və başladıldı.'
} catch {
    if ($NativeInstallation) { throw 'Windows xidmətlərini idarə edən backup worker administrator tərəfindən quraşdırılmalıdır.' }
    # A user-level Docker installation can also start the worker at interactive logon.
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($startupLink)
    $shortcut.TargetPath = (Get-Command powershell.exe).Source
    $shortcut.Arguments = $arguments
    $shortcut.WindowStyle = 7
    $shortcut.WorkingDirectory = $ProjectRoot
    $shortcut.Save()
    Start-Process -FilePath $shortcut.TargetPath -ArgumentList $arguments -WindowStyle Hidden | Out-Null
    Write-Output 'Backup worker istifadəçi girişində açılacaq; cari sessiyada gizli başladıldı.'
}
