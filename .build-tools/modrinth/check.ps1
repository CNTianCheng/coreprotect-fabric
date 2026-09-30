<#
    check.ps1 -- read-only Modrinth status for the CoreProtect Fabric project.

    ASCII only. Prints the authenticated user, the project (if it exists) and every
    published version, so the result can be compared with what publish.ps1 was asked
    to upload. All non-ASCII values are printed as JSON escapes, which keeps the
    console output pure ASCII.

    Usage:
        powershell -ExecutionPolicy Bypass -File .build-tools\modrinth\check.ps1
#>
[CmdletBinding()]
param(
    [string]$Token,
    [string]$Slug = 'coreprotect-fabric'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$here = $PSScriptRoot
$api = 'https://api.modrinth.com/v2'
$ua = 'CNTianCheng/coreprotect-fabric (https://github.com/CNTianCheng/coreprotect-fabric)'

if (-not $Token) { $Token = $env:MODRINTH_TOKEN }
if (-not $Token) {
    $tokenFile = Join-Path $here 'token.txt'
    if (Test-Path -LiteralPath $tokenFile) {
        foreach ($line in [System.IO.File]::ReadAllLines($tokenFile)) {
            $candidate = $line.Trim()
            if ($candidate -and -not $candidate.StartsWith('#')) { $Token = $candidate; break }
        }
    }
}

function Invoke-Api {
    param([string]$Path)
    $headers = @{ 'User-Agent' = $ua }
    if ($Token) { $headers['Authorization'] = $Token }
    return (Invoke-RestMethod -Uri "$api$Path" -Method Get -Headers $headers -TimeoutSec 60)
}

Write-Host '== Modrinth status =='

try {
    $user = Invoke-Api -Path '/user'
    Write-Host ("user     : " + (([ordered]@{ id = $user.id; username = $user.username; role = $user.role } | ConvertTo-Json -Compress)))
} catch {
    Write-Host "user     : not available (the token lacks USER_READ, or is invalid)"
}

$project = $null
try {
    $project = Invoke-Api -Path "/project/$Slug"
} catch {
    Write-Host "project  : '$Slug' NOT FOUND (404) - it can still be created"
}

if ($project) {
    $summary = [ordered]@{
        id           = $project.id
        slug         = $project.slug
        title        = $project.title
        project_type = $project.project_type
        status       = $project.status
        license      = $project.license.id
        icon_url     = $project.icon_url
        source_url   = $project.source_url
        categories   = @($project.categories)
        versions     = @($project.versions).Count
        published    = $project.published
        updated      = $project.updated
        body_chars   = 0
    }
    if ($project.body) { $summary['body_chars'] = $project.body.Length }
    Write-Host ("project  : " + ($summary | ConvertTo-Json -Compress))

    $versions = @()
    try { $versions = @(Invoke-Api -Path "/project/$Slug/version") } catch { }
    Write-Host ("versions : " + $versions.Count)
    foreach ($v in $versions) {
        $files = @()
        foreach ($f in @($v.files)) {
            $files += [ordered]@{ name = $f.filename; size = $f.size; primary = $f.primary }
        }
        $line = [ordered]@{
            version_number = $v.version_number
            name           = $v.name
            game_versions  = @($v.game_versions)
            loaders        = @($v.loaders)
            version_type   = $v.version_type
            status         = $v.status
            date_published = $v.date_published
            downloads      = $v.downloads
            files          = $files
        }
        Write-Host ("  " + ($line | ConvertTo-Json -Compress -Depth 5))
    }
}

Write-Host 'CHECK_DONE'
