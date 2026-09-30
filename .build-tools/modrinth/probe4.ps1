<#
    probe4.ps1 -- let the API dictate the required payload fields for POST /version.

    ASCII only. Sends the payload with the game version forced to "9.99" so no version
    can be created, reads the "missing field `X`" error, adds X with a default, and
    repeats until the payload parses.
#>
[CmdletBinding()]
param(
    [string]$Token,
    [int]$MaxRounds = 16
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$here = $PSScriptRoot
$api = 'https://api.modrinth.com/v2'
$ua = 'CNTianCheng/coreprotect-fabric (https://github.com/CNTianCheng/coreprotect-fabric)'

if (-not $Token) {
    foreach ($line in [System.IO.File]::ReadAllLines((Join-Path $here 'token.txt'))) {
        $candidate = $line.Trim()
        if ($candidate -and -not $candidate.StartsWith('#')) { $Token = $candidate; break }
    }
}

$defaults = @{
    'version_title' = 'CoreProtect-Fabric 1.9.0'
    'featured'      = $false
    'status'        = 'listed'
    'environment'   = 'dedicated_server_only'
    'dependencies'  = @()
    'changelog'     = 'probe'
    'name'          = 'CoreProtect-Fabric 1.9.0'
    'version_number' = '1.9.0-probe'
    'game_versions' = @('9.99')
    'loaders'       = @('fabric')
    'version_type'  = 'release'
    'project_id'    = 'SXIMAuWt'
    'file_parts'    = @('file')
    'primary_file'  = 'file'
    'file_types'    = @{ file = 'unknown' }
    'featured_flag' = $false
}

$payload = [ordered]@{
    version_number = '1.9.0-probe'
    changelog      = 'probe'
    game_versions  = @('9.99')
    loaders        = @('fabric')
    version_type   = 'release'
    project_id     = 'SXIMAuWt'
    file_parts     = @('file')
    primary_file   = 'file'
}

function Post-Payload {
    param([System.Collections.Specialized.OrderedDictionary]$Body)
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(10)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('Authorization', $Token)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('User-Agent', $ua)
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $stream = $null
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $json = ($Body | ConvertTo-Json -Compress -Depth 6)
        $form.Add((New-Object System.Net.Http.StringContent($json, $utf8, 'application/json')), 'data')
        $stream = [System.IO.File]::OpenRead((Join-Path $here 'dummy.jar'))
        $form.Add((New-Object System.Net.Http.StreamContent($stream)), 'file', 'coreprotect-fabric-1.21-1.9.0.jar')
        $response = $client.PostAsync("$api/version", $form).Result
        return @{ Code = [int]$response.StatusCode; Text = $response.Content.ReadAsStringAsync().Result }
    } finally {
        if ($stream) { $stream.Dispose() }
        $form.Dispose()
        $client.Dispose()
    }
}

for ($round = 1; $round -le $MaxRounds; $round++) {
    $result = Post-Payload -Body $payload
    $text = $result.Text
    if ($text.Length -gt 260) { $shown = $text.Substring(0, 260) + '...' } else { $shown = $text }
    Write-Host ("round {0,2}  HTTP {1} : {2}" -f $round, $result.Code, $shown)
    if ($result.Code -ne 400) { break }

    if ($text -match 'missing field .([A-Za-z_]+).') {
        $field = $Matches[1]
        if ($payload.Contains($field)) {
            Write-Host ("  -> field '{0}' is already present; stopping." -f $field)
            break
        }
        if ($defaults.ContainsKey($field)) {
            $payload[$field] = $defaults[$field]
        } else {
            $payload[$field] = $false
            Write-Host ("  -> no default known for '{0}'; sending false." -f $field)
        }
        Write-Host ("  -> added '{0}'" -f $field)
        continue
    }
    Write-Host '  -> not a missing-field error; stopping.'
    break
}

Write-Host '--- accepted field set ---'
$payload.Keys | ForEach-Object { Write-Host ("  {0} = {1}" -f $_, (($payload[$_] | ConvertTo-Json -Compress -Depth 4))) }
Write-Host 'PROBE4_DONE'
