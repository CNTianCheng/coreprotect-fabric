<#
    probe7.ps1 -- isolate the request-shape difference between publish.ps1 and probe6.ps1.

    ASCII only. Every case sends project_id AAAAAAAA (a project that does not exist), so
    no version can be created whatever the result: a working request shape answers 404,
    a broken one answers the actix 405.
#>
[CmdletBinding()]
param(
    [string]$Token
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$here = $PSScriptRoot
$api = 'https://api.modrinth.com/v2'
$ua = 'CNTianCheng/coreprotect-fabric (https://github.com/CNTianCheng/coreprotect-fabric)'

if (-not $Token) {
    $lines = @(Get-Content -LiteralPath (Join-Path $here 'token.txt') | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') })
    $Token = $lines[0].Trim()
}

$jar = Join-Path (Resolve-Path (Join-Path $here '..\..')).Path 'coreprotect-fabric-1.21\build\libs\coreprotect-fabric-1.21-1.9.0.jar'
$json = [System.IO.File]::ReadAllText((Join-Path $here 'payload-1.json'), [System.Text.Encoding]::UTF8)
$json = $json -replace '"project_id":"SXIMAuWt"', '"project_id":"AAAAAAAA"'
Write-Host ("payload: {0} chars, bogus project id injected: {1}" -f $json.Length, $json.Contains('AAAAAAAA'))

function New-Client {
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(60)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('Authorization', $Token)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('User-Agent', $ua)
    return $client
}

# Verbatim copy of publish.ps1's Send-Multipart.
function Send-PublishShape {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Json,
        [hashtable]$Files,
        [switch]$ExplicitType
    )
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $streams = @()
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $data = New-Object System.Net.Http.StringContent($Json, $utf8, 'application/json')
        $form.Add($data, 'data')
        foreach ($field in $Files.Keys) {
            $path = $Files[$field]
            $stream = [System.IO.File]::OpenRead($path)
            $streams += $stream
            $content = New-Object System.Net.Http.StreamContent($stream)
            if ($ExplicitType) {
                $content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue('application/octet-stream')
            }
            $form.Add($content, $field, [System.IO.Path]::GetFileName($path))
        }
        $response = $Client.PostAsync("$api/version", $form).Result
        return @{ Status = [int]$response.StatusCode; Text = $response.Content.ReadAsStringAsync().Result }
    } finally {
        foreach ($stream in $streams) { $stream.Dispose() }
        $form.Dispose()
    }
}

# Verbatim copy of probe6.ps1's Send-Dump request.
function Send-ProbeShape {
    param([System.Net.Http.HttpClient]$Client)
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $stream = $null
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $form.Add((New-Object System.Net.Http.StringContent($Json, $utf8, 'application/json')), 'data')
        $stream = [System.IO.File]::OpenRead($jar)
        $form.Add((New-Object System.Net.Http.StreamContent($stream)), 'file', [System.IO.Path]::GetFileName($jar))
        $response = $Client.PostAsync("$api/version", $form).Result
        return @{ Status = [int]$response.StatusCode; Text = $response.Content.ReadAsStringAsync().Result }
    } finally {
        if ($stream) { $stream.Dispose() }
        $form.Dispose()
    }
}

function Show {
    param([string]$Label, $Result)
    $text = $Result.Text
    if ($text.Length -gt 160) { $text = $text.Substring(0, 160) + '...' }
    Write-Host ("{0,-44} HTTP {1} : {2}" -f $Label, $Result.Status, $text)
}

$shared = New-Client
try {
    Show -Label 'A probe shape, fresh client' (Send-ProbeShape -Client (New-Client))
    Show -Label 'B publish shape, fresh client' (Send-PublishShape -Client (New-Client) -Json $json -Files @{ file = $jar })
    Show -Label 'C publish shape, no explicit type' (Send-PublishShape -Client (New-Client) -Json $json -Files @{ file = $jar } -ExplicitType:$false)
    Show -Label 'D publish shape, shared client #1' (Send-PublishShape -Client $shared -Json $json -Files @{ file = $jar })
    Show -Label 'E publish shape, shared client #2' (Send-PublishShape -Client $shared -Json $json -Files @{ file = $jar })
    Show -Label 'F probe shape, shared client' (Send-ProbeShape -Client $shared)
} finally {
    $shared.Dispose()
}
Write-Host 'PROBE7_DONE'
