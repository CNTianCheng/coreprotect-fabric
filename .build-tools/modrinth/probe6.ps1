<#
    probe6.ps1 -- replay publish.ps1's dumped payloads through the probe's request code.

    ASCII only. Sends payload-N.json (written by publish.ps1 -DumpPayload) with the real
    jar, first without an explicit Content-Type on the file part (the probe5 shape, which
    returned HTTP 200) and then with application/octet-stream (the publish.ps1 shape).
#>
[CmdletBinding()]
param(
    [string]$Token,
    [string]$Root,
    [int]$Max = 3
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$here = $PSScriptRoot
$api = 'https://api.modrinth.com/v2'
$ua = 'CNTianCheng/coreprotect-fabric (https://github.com/CNTianCheng/coreprotect-fabric)'
if (-not $Root) { $Root = (Resolve-Path (Join-Path $here '..\..')).Path }

if (-not $Token) {
    $lines = @(Get-Content -LiteralPath (Join-Path $here 'token.txt') | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') })
    $Token = $lines[0].Trim()
}
$versions = ([System.IO.File]::ReadAllText((Join-Path $here 'versions.json'), [System.Text.Encoding]::UTF8)) | ConvertFrom-Json

function Send-Dump {
    param(
        [int]$Index,
        [switch]$ExplicitType
    )
    $n = $Index - 1
    $payloadPath = Join-Path $here ("payload-{0}.json" -f $Index)
    $json = [System.IO.File]::ReadAllText($payloadPath, [System.Text.Encoding]::UTF8)
    $v = $versions[$n]
    $jar = Join-Path $Root ($v.file -replace '/', '\')
    $label = "payload-$Index $(if ($ExplicitType) { 'octet-stream' } else { 'default'      })"

    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(20)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('Authorization', $Token)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('User-Agent', $ua)
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $stream = $null
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $form.Add((New-Object System.Net.Http.StringContent($json, $utf8, 'application/json')), 'data')
        $stream = [System.IO.File]::OpenRead($jar)
        $content = New-Object System.Net.Http.StreamContent($stream)
        if ($ExplicitType) {
            $content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue('application/octet-stream')
        }
        $form.Add($content, 'file', [System.IO.Path]::GetFileName($jar))
        $response = $client.PostAsync("$api/version", $form).Result
        $text = $response.Content.ReadAsStringAsync().Result
        if ([int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 300) {
            $obj = $text | ConvertFrom-Json
            Write-Host ("{0,-34} HTTP {1} : CREATED id={2} {3} mc={4}" -f $label, [int]$response.StatusCode, $obj.id, $obj.version_number, ($obj.game_versions -join ','))
        } else {
            if ($text.Length -gt 200) { $text = $text.Substring(0, 200) + '...' }
            Write-Host ("{0,-34} HTTP {1} : {2}" -f $label, [int]$response.StatusCode, $text)
        }
    } catch {
        Write-Host ("{0,-34} EXCEPTION : {1}" -f $label, $_.Exception.Message)
    } finally {
        if ($stream) { $stream.Dispose() }
        $form.Dispose()
        $client.Dispose()
    }
}

# payload-1 first tests both request shapes (the explicit one only as a diagnostic,
# because whichever succeeds creates version 1.9.0 for 1.21); the other two payloads
# are then sent only in the default shape.
for ($i = 1; $i -le $Max; $i++) {
    $payloadPath = Join-Path $here ("payload-{0}.json" -f $i)
    if (-not (Test-Path -LiteralPath $payloadPath)) { continue }
    if ($i -eq 1) { Send-Dump -Index $i -ExplicitType }
    Send-Dump -Index $i
}
Write-Host 'PROBE6_DONE'
