<#
    probe5.ps1 -- find which addition turns the accepted payload into an actix 405.

    ASCII only. Starts from the payload shape the API accepted in probe4 (it answered
    "not a valid variant for game_versions" for game version 9.99) and adds one field
    at a time with the real game version. Any case that unexpectedly succeeds prints
    MUST BE DELETED with the new version id.
#>
[CmdletBinding()]
param(
    [string]$Token,
    [string]$Root
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$here = $PSScriptRoot
$api = 'https://api.modrinth.com/v2'
$ua = 'CNTianCheng/coreprotect-fabric (https://github.com/CNTianCheng/coreprotect-fabric)'
if (-not $Root) { $Root = (Resolve-Path (Join-Path $here '..\..')).Path }

if (-not $Token) {
    foreach ($line in [System.IO.File]::ReadAllLines((Join-Path $here 'token.txt'))) {
        $candidate = $line.Trim()
        if ($candidate -and -not $candidate.StartsWith('#')) { $Token = $candidate; break }
    }
}

$changelog = [System.IO.File]::ReadAllText((Join-Path $here 'changelog-1.9.0.md'), [System.Text.Encoding]::UTF8)
$realJar = Join-Path $Root 'coreprotect-fabric-1.21\build\libs\coreprotect-fabric-1.21-1.9.0.jar'

# A throwaway 1 KB "jar" for the cases that only care about the payload: the API answers
# 400 "Unable to read Zip Archive" for it, which is exactly what those cases need.
$dummyPath = Join-Path $here 'dummy.jar'
if (-not (Test-Path -LiteralPath $dummyPath)) {
    [System.IO.File]::WriteAllBytes($dummyPath, (New-Object byte[] 1024))
}

function Send-Case {
    param(
        [string]$Label,
        [int]$Mc,
        [hashtable]$Extra,
        [switch]$RealFile,
        [string]$VersionNumber = '1.9.0-probe'
    )
    # NOTE: switch output is unrolled, so a one-element array must be re-wrapped
    # with @(...) or ConvertTo-Json emits a bare string instead of a sequence.
    $games = @(switch ($Mc) {
        1 { '1.21' }
        default { '9.99' }
    })
    $payload = [ordered]@{
        version_title  = 'CoreProtect-Fabric 1.9.0 probe'
        version_number = $VersionNumber
        changelog      = $changelog
        game_versions  = $games
        loaders        = @('fabric')
        version_type   = 'release'
        project_id     = 'SXIMAuWt'
        file_parts     = @('file')
        primary_file   = 'file'
        dependencies   = @([ordered]@{ project_id = 'P7dR8mSH'; dependency_type = 'required' })
        featured       = $false
    }
    if ($Extra) { foreach ($key in $Extra.Keys) { $payload[$key] = $Extra[$key] } }

    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(10)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('Authorization', $Token)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('User-Agent', $ua)
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $stream = $null
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $json = ($payload | ConvertTo-Json -Compress -Depth 6)
        $form.Add((New-Object System.Net.Http.StringContent($json, $utf8, 'application/json')), 'data')
        $path = if ($RealFile) { $realJar } else { Join-Path $here 'dummy.jar' }
        $stream = [System.IO.File]::OpenRead($path)
        $form.Add((New-Object System.Net.Http.StreamContent($stream)), 'file', 'coreprotect-fabric-1.21-1.9.0.jar')
        $response = $client.PostAsync("$api/version", $form).Result
        $text = $response.Content.ReadAsStringAsync().Result
        $note = ''
        if ([int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 300) {
            $obj = $text | ConvertFrom-Json
            $note = "  <== CREATED $($obj.version_number) id=$($obj.id) status=$($obj.status) - MUST BE DELETED"
        }
        if ($text.Length -gt 200) { $text = $text.Substring(0, 200) + '...' }
        Write-Host ("{0,-40} HTTP {1} : {2}{3}" -f $Label, [int]$response.StatusCode, $text, $note)
    } catch {
        Write-Host ("{0,-40} EXCEPTION : {1}" -f $Label, $_.Exception.Message)
    } finally {
        if ($stream) { $stream.Dispose() }
        $form.Dispose()
        $client.Dispose()
    }
}

Write-Host '--- probe5 (every success must be deleted afterwards) ---'
Send-Case -Label 'A minimal + real mc 1.21' -Mc 1
Send-Case -Label 'B minimal + real mc + status listed' -Mc 1 -Extra @{ status = 'listed' }
Send-Case -Label 'C minimal + real mc + environment' -Mc 1 -Extra @{ environment = 'dedicated_server_only' }
Send-Case -Label 'D minimal + real mc + real 13.6MB jar' -Mc 1 -RealFile
Send-Case -Label 'E bogus mc + status listed (control)' -Mc 2 -Extra @{ status = 'listed' }
Write-Host 'PROBE5_DONE'
