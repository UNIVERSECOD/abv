[CmdletBinding()]
param(
    [int]$Port = 18765
)

$ErrorActionPreference = 'Stop'

if ([Threading.Thread]::CurrentThread.GetApartmentState() -ne [Threading.ApartmentState]::STA) {
    throw 'Qovluq seçici STA rejimində başladılmalıdır.'
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$allowedOrigins = @(
    'http://localhost:3000',
    'http://127.0.0.1:3000',
    'http://localhost:3001',
    'http://127.0.0.1:3001'
)
$createdNew = $false
$singleInstance = [Threading.Mutex]::new(
    $true,
    'Local\AttendraBackupFolderPicker',
    [ref]$createdNew)

if (-not $createdNew) {
    $singleInstance.Dispose()
    exit 0
}

function Set-CorsHeaders {
    param(
        [Parameter(Mandatory = $true)][Net.HttpListenerResponse]$Response,
        [Parameter(Mandatory = $true)][string]$Origin
    )

    $Response.Headers['Access-Control-Allow-Origin'] = $Origin
    $Response.Headers['Access-Control-Allow-Methods'] = 'POST, OPTIONS'
    $Response.Headers['Access-Control-Allow-Headers'] = 'Content-Type'
    $Response.Headers['Access-Control-Allow-Private-Network'] = 'true'
    $Response.Headers['Vary'] = 'Origin'
    $Response.Headers['Cache-Control'] = 'no-store'
}

function Write-JsonResponse {
    param(
        [Parameter(Mandatory = $true)][Net.HttpListenerResponse]$Response,
        [Parameter(Mandatory = $true)][int]$StatusCode,
        [Parameter(Mandatory = $true)][object]$Payload
    )

    $json = $Payload | ConvertTo-Json -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $Response.StatusCode = $StatusCode
    $Response.ContentType = 'application/json; charset=utf-8'
    $Response.ContentLength64 = $bytes.Length
    $Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $Response.OutputStream.Close()
}

function Select-BackupFolder {
    param([AllowEmptyString()][string]$CurrentPath)

    $dialog = New-Object Windows.Forms.FolderBrowserDialog
    $dialog.Description = 'Attendra backup qovluğunu seçin'
    $dialog.ShowNewFolderButton = $true
    if ($CurrentPath -and (Test-Path -LiteralPath $CurrentPath -PathType Container)) {
        $dialog.SelectedPath = $CurrentPath
    }

    $owner = New-Object Windows.Forms.Form
    $owner.ShowInTaskbar = $false
    $owner.TopMost = $true
    $owner.Opacity = 0
    $owner.FormBorderStyle = [Windows.Forms.FormBorderStyle]::FixedToolWindow
    $owner.StartPosition = [Windows.Forms.FormStartPosition]::CenterScreen
    $owner.Size = New-Object Drawing.Size(1, 1)

    try {
        $owner.Show()
        $owner.Activate()
        $result = $dialog.ShowDialog($owner)
        if ($result -eq [Windows.Forms.DialogResult]::OK) {
            return $dialog.SelectedPath
        }
        return $null
    } finally {
        $dialog.Dispose()
        $owner.Close()
        $owner.Dispose()
    }
}

$listener = New-Object Net.HttpListener
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.IgnoreWriteExceptions = $true

try {
    $listener.Start()
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $request = $context.Request
        $response = $context.Response
        $origin = [string]$request.Headers['Origin']

        if ($allowedOrigins -notcontains $origin) {
            Write-JsonResponse -Response $response -StatusCode 403 -Payload @{
                error = 'Bu sorğuya icazə verilmir.'
            }
            continue
        }

        Set-CorsHeaders -Response $response -Origin $origin

        if ($request.HttpMethod -eq 'OPTIONS') {
            $response.StatusCode = 204
            $response.Close()
            continue
        }

        if ($request.HttpMethod -ne 'POST' -or $request.Url.AbsolutePath -ne '/select-folder') {
            Write-JsonResponse -Response $response -StatusCode 404 -Payload @{
                error = 'Sorğu tapılmadı.'
            }
            continue
        }

        try {
            $reader = New-Object IO.StreamReader($request.InputStream, $request.ContentEncoding)
            try {
                $body = $reader.ReadToEnd()
            } finally {
                $reader.Dispose()
            }
            $payload = if ($body) { $body | ConvertFrom-Json } else { $null }
            $selectedPath = Select-BackupFolder -CurrentPath ([string]$payload.currentPath)
            if ($selectedPath) {
                Write-JsonResponse -Response $response -StatusCode 200 -Payload @{
                    cancelled = $false
                    path = $selectedPath
                }
            } else {
                Write-JsonResponse -Response $response -StatusCode 200 -Payload @{
                    cancelled = $true
                }
            }
        } catch {
            Write-JsonResponse -Response $response -StatusCode 500 -Payload @{
                error = 'Qovluq seçicini açmaq mümkün olmadı.'
            }
        }
    }
} finally {
    if ($listener.IsListening) {
        $listener.Stop()
    }
    $listener.Close()
    $singleInstance.ReleaseMutex()
    $singleInstance.Dispose()
}
