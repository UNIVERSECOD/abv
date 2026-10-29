param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$')][string]$Version,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$ApplicationCommit,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repo "build\abv-ubuntu-$Version" }
$destination = [IO.Path]::GetFullPath($OutputDirectory)
$bundle = "$destination.tar.gz"
if ((Test-Path -LiteralPath $destination) -or (Test-Path -LiteralPath $bundle)) {
    throw 'Output already exists. Choose a new version or output directory.'
}
& git -C $repo cat-file -e "$ApplicationCommit^{commit}"
if ($LASTEXITCODE -ne 0) { throw 'Application commit is not available locally.' }
$images = @()
foreach ($service in @('backend', 'isapi', 'frontend')) {
    $source = "abv-${service}:latest"
    $details = (& docker image inspect $source | ConvertFrom-Json)[0]
    if ($LASTEXITCODE -ne 0 -or !$details) { throw "Missing verified image: $source" }
    if ($details.Os -ne 'linux' -or $details.Architecture -ne 'amd64') { throw "Unsupported platform: $source" }
    $tag = "abv-${service}:$Version"
    # Resolve latest once; the versioned export cannot drift during docker save.
    & docker image tag $details.Id $tag
    if ($LASTEXITCODE -ne 0) { throw "Tag failed: $source" }
    $images += [ordered]@{ tag = $tag; id = $details.Id; platform = 'linux/amd64' }
}
$database = (& docker image inspect postgres:15-alpine | ConvertFrom-Json)[0]
if ($LASTEXITCODE -ne 0 -or !$database) { throw 'Local PostgreSQL image is missing.' }
if ($database.Os -ne 'linux' -or $database.Architecture -ne 'amd64') { throw 'Unsupported PostgreSQL platform.' }
$images += [ordered]@{ tag = 'postgres:15-alpine'; id = $database.Id; platform = 'linux/amd64' }
New-Item -ItemType Directory -Path $destination | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
# Explicit allowlist: no repository, source tree, customer runtime or real .env.
foreach ($name in @('compose.yaml', 'init.sql', 'prepare.sh', 'install.sh', 'README.md')) {
    $content = [IO.File]::ReadAllText((Join-Path $repo "deploy\ubuntu-images\$name"))
    $content = $content.Replace("`r`n", "`n").Replace('__ABV_VERSION__', $Version)
    [IO.File]::WriteAllText((Join-Path $destination $name), $content, $utf8)
}
$manifest = [ordered]@{
    product = 'Attendra ABV'
    version = $Version
    applicationCommit = $ApplicationCommit
    platform = 'linux/amd64'
    images = $images
}
[IO.File]::WriteAllText((Join-Path $destination 'images.json'), ($manifest | ConvertTo-Json -Depth 5), $utf8)
$tags = @($images | ForEach-Object { $_.tag })
& docker image save --output (Join-Path $destination 'abv-images.tar') @tags
if ($LASTEXITCODE -ne 0) { throw 'Image export failed. Do not deliver this incomplete directory.' }
$sums = @()
foreach ($file in (Get-ChildItem -LiteralPath $destination -File | Sort-Object Name)) {
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $sums += "$hash  $($file.Name)"
}
[IO.File]::WriteAllText((Join-Path $destination 'SHA256SUMS'), (($sums -join "`n") + "`n"), $utf8)
& tar.exe -czf $bundle -C $destination .
if ($LASTEXITCODE -ne 0) { throw 'Bundle compression failed.' }
$bundleHash = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$bundle.sha256", "$bundleHash  $([IO.Path]::GetFileName($bundle))`n", $utf8)
Write-Output "Delivery ready: $bundle"
Write-Output "SHA256: $bundleHash"
Write-Output 'Only final runtime images and allowlisted installation files were exported.'
