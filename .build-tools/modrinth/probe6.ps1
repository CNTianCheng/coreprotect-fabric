<#
    probe6.ps1 -- replay publish.ps1's dumped payloads through the probe's request code.

    ASCII only. Sends payload-N.json (written by publish.ps1 -DumpPayload) together with the
    real jar, using the default multipart shape: no explicit Content-Type on the file part,
    which is the shape api.modrinth.com answers with HTTP 200.

    One payload means exactly one upload. An earlier version of this script also re-sent
    payload-1 with an explicit application/octet-stream Content-Type as a diagnostic, which
    created a second Modrinth version on every release (the extra 1.9.2 version had to be
    deleted by hand); that second upload is gone and the code path no longer exists.
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
        [int]$Index
    )
    $n = $Index - 1
    $payloadPath = Join-Path $here ("payload-{0}.json" -f $Index)
    $json = [System.IO.File]::ReadAllText($payloadPath, [System.Text.Encoding]::UTF8)
    $v = $versions[$n]
    $jar = Join-Path $Root ($v.file -replace '/', '\')
    $label = "payload-$Index"

    if (-not (Test-Path -LiteralPath $jar)) {
        Write-Host ("{0,-34} SKIP     : no jar at {1}" -f $label, $jar)
        return
    }

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

# One upload per payload - see the header: never send the same payload twice.
for ($i = 1; $i -le $Max; $i++) {
    $payloadPath = Join-Path $here ("payload-{0}.json" -f $i)
    if (-not (Test-Path -LiteralPath $payloadPath)) { continue }
    Send-Dump -Index $i
}
Write-Host 'PROBE6_DONE'
