param([Parameter(Mandatory=$true)][string]$JavaHome,
      [Parameter(Mandatory=$true)][string]$NsisDirectory,
      [Parameter(Mandatory=$true)][string]$DependenciesDirectory,
      [string]$Version = '1.0.0', [switch]$UseBuiltArtifacts)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Version must be x.y.z' }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$output = Join-Path $repo 'build\windows-installer'
$stage = Join-Path $output ("payload-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $stage | Out-Null
if (-not $UseBuiltArtifacts) {
    $env:JAVA_HOME = $JavaHome
    $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
    foreach ($module in @('backend','isapi')) {
        Push-Location (Join-Path $repo $module)
        try { & mvn.cmd package; if ($LASTEXITCODE) { throw "$module build failed" } } finally { Pop-Location }
    }
    Push-Location "$repo\frontend"
    try {
        & npm.cmd ci; if ($LASTEXITCODE) { throw 'npm ci failed' }
        & npm.cmd run lint; if ($LASTEXITCODE) { throw 'Frontend lint failed' }
        & npm.cmd run build; if ($LASTEXITCODE) { throw 'Frontend build failed' }
    } finally { Pop-Location }
}
foreach ($dir in @('app','web','tools','scripts','licenses')) { New-Item -ItemType Directory "$stage\$dir" | Out-Null }
& "$PSScriptRoot\Build-BrandAssets.ps1" -Source "$repo\frontend\public\attendra-mark.png" -Destination "$stage\assets"
Copy-Item "$repo\backend\target\hr-erp-1.0.0-SNAPSHOT.jar" "$stage\app\backend.jar"
Copy-Item "$repo\isapi\target\hr-erp-isapi-1.0.0-SNAPSHOT.jar" "$stage\app\isapi.jar"
Copy-Item "$repo\frontend\dist" "$stage\web\dist" -Recurse
Copy-Item "$DependenciesDirectory\postgres" "$stage\postgres" -Recurse
Copy-Item "$DependenciesDirectory\caddy\caddy.exe" "$stage\web\caddy.exe"
Copy-Item "$DependenciesDirectory\WinSW.exe" "$stage\tools\WinSW.exe"
Copy-Item "$DependenciesDirectory\vc_redist.x64.exe" "$stage\tools\vc_redist.x64.exe"
Copy-Item "$DependenciesDirectory\caddy\LICENSE" "$stage\licenses\Caddy-LICENSE" -ErrorAction Stop
foreach ($script in @('Runtime.ps1','Manage.ps1','Start-Component.ps1','Open-Attendra.ps1')) {
    Copy-Item "$PSScriptRoot\$script" "$stage\scripts"
}
Copy-Item "$repo\scripts\backup-attendra.ps1" "$stage\scripts"
Copy-Item "$repo\scripts\backup-folder-picker.ps1" "$stage\scripts"
foreach ($script in @('backup-worker.ps1', 'backup-operations.ps1', 'install-backup-worker.ps1')) {
    Copy-Item "$repo\scripts\$script" "$stage\scripts"
}
Copy-Item "$PSScriptRoot\THIRD_PARTY.md" "$stage\licenses"
Copy-Item "$DependenciesDirectory\WinSW-LICENSE.txt" "$stage\licenses"
& "$JavaHome\bin\jlink.exe" --add-modules ALL-MODULE-PATH --strip-debug --no-header-files --no-man-pages --output "$stage\java"
if ($LASTEXITCODE) { throw 'Bundled Java runtime build failed' }
$release = @{ version = $Version; builtAt = [DateTimeOffset]::UtcNow.ToString('o'); commit = (& git -C $repo rev-parse HEAD) }
$release | ConvertTo-Json | Set-Content "$stage\release.json" -Encoding UTF8
$files = Get-ChildItem -LiteralPath $stage -File -Recurse | ForEach-Object {
    @{ path = $_.FullName.Substring($stage.Length + 1); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash }
}
$files | ConvertTo-Json -Depth 3 | Set-Content "$stage\files.json" -Encoding UTF8
$setup = "$output\Attendra-Setup-$Version.exe"
& "$NsisDirectory\makensis.exe" "/DPAYLOAD=$stage" "/DOUTPUT=$setup" "/DVERSION=$Version" "$PSScriptRoot\Attendra.nsi"
if ($LASTEXITCODE) { throw 'Setup compilation failed' }
Get-FileHash $setup | ForEach-Object { "$($_.Hash)  $([IO.Path]::GetFileName($setup))" } | Set-Content "$setup.sha256"
Write-Output "Setup: $setup"
Write-Output "Payload: $stage"
