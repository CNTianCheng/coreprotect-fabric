<#
    publish.ps1 -- publish the CoreProtect Fabric builds to Modrinth.

    ASCII only (see .build-tools/check-ascii.ps1): every Chinese string lives in the
    UTF-8 data files next to this script (body.md, changelog-*.md, project.json,
    versions.json) and is read with [System.IO.File]::ReadAllText(..., UTF8), because
    Windows PowerShell 5.1 would otherwise read this file as ANSI.

    Usage:
        powershell -ExecutionPolicy Bypass -File .build-tools\modrinth\publish.ps1
        powershell -ExecutionPolicy Bypass -File .build-tools\modrinth\publish.ps1 -DryRun
        powershell -ExecutionPolicy Bypass -File .build-tools\modrinth\publish.ps1 -Force

    The token is a Modrinth personal access token (mrp_...) with the PROJECT_CREATE,
    PROJECT_WRITE and VERSION_CREATE scopes. Pass it with -Token, put it in the
    MODRINTH_TOKEN environment variable, or write it to
    .build-tools\modrinth\token.txt (gitignored).

    Idempotent: an existing project is updated instead of recreated, and a version
    whose version_number is already published is skipped unless -Force is given.
    Windows PowerShell 5.1 has no -Form on Invoke-RestMethod, hence the HttpClient
    multipart uploads below.
#>
[CmdletBinding()]
param(
    [string]$Token,
    [string]$Root,
    [switch]$DryRun,
    [switch]$NoProjectUpdate,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$here = $PSScriptRoot
$api = 'https://api.modrinth.com/v2'
$ua = 'CNTianCheng/coreprotect-fabric (https://github.com/CNTianCheng/coreprotect-fabric)'
if (-not $Root) { $Root = (Resolve-Path (Join-Path $here '..\..')).Path }

function Read-Utf8([string]$Path) {
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function To-JsonText($Object) {
    return ($Object | ConvertTo-Json -Depth 12 -Compress)
}

function Get-ErrorText($ErrorRecord) {
    $parts = @()
    if ($ErrorRecord.ErrorDetails -and $ErrorRecord.ErrorDetails.Message) {
        $parts += $ErrorRecord.ErrorDetails.Message
    }
    $resp = $ErrorRecord.Exception.Response
    if ($resp) {
        try {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $parts += $reader.ReadToEnd()
        } catch { }
    }
    if ($parts.Count -eq 0) { $parts += $ErrorRecord.Exception.Message }
    return ($parts -join ' | ')
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Json
    )
    $headers = @{ 'User-Agent' = $ua }
    if ($Token) { $headers['Authorization'] = $Token }
    $params = @{
        Uri        = "$api$Path"
        Method     = $Method
        Headers    = $headers
        TimeoutSec = 180
    }
    if ($PSBoundParameters.ContainsKey('Json')) {
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($Json)
        $params['ContentType'] = 'application/json'
    }
    return (Invoke-RestMethod @params)
}

function Send-Multipart {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Path,
        [string]$Json,
        [hashtable]$Files
    )
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $streams = @()
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $data = New-Object System.Net.Http.StringContent($Json, $utf8, 'application/json')
        $form.Add($data, 'data')
        foreach ($field in $Files.Keys) {
            $path = $Files[$field]
            if (-not (Test-Path -LiteralPath $path)) { throw "file not found: $path" }
            $stream = [System.IO.File]::OpenRead($path)
            $streams += $stream
            $content = New-Object System.Net.Http.StreamContent($stream)
            $content.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue('application/octet-stream')
            $form.Add($content, $field, [System.IO.Path]::GetFileName($path))
        }
        $response = $Client.PostAsync("$api$Path", $form).Result
        $text = $response.Content.ReadAsStringAsync().Result
        return @{ Status = [int]$response.StatusCode; Text = $text }
    } finally {
        foreach ($stream in $streams) { $stream.Dispose() }
        $form.Dispose()
    }
}

function Send-MultipartPatch {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Path,
        [hashtable]$Files
    )
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $streams = @()
    try {
        foreach ($field in $Files.Keys) {
            $path = $Files[$field]
            if (-not (Test-Path -LiteralPath $path)) { throw "file not found: $path" }
            $stream = [System.IO.File]::OpenRead($path)
            $streams += $stream
            $content = New-Object System.Net.Http.StreamContent($stream)
            $form.Add($content, $field, [System.IO.Path]::GetFileName($path))
        }
        $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Patch, "$api$Path")
        $request.Content = $form
        $response = $Client.SendAsync($request).Result
        $text = $response.Content.ReadAsStringAsync().Result
        return @{ Status = [int]$response.StatusCode; Text = $text }
    } finally {
        foreach ($stream in $streams) { $stream.Dispose() }
        $form.Dispose()
    }
}

# ---------------------------------------------------------------- input files

$project = (Read-Utf8 (Join-Path $here 'project.json')) | ConvertFrom-Json
$versions = (Read-Utf8 (Join-Path $here 'versions.json')) | ConvertFrom-Json
$bodyText = Read-Utf8 (Join-Path $here $project.body_file)
$changelogText = Read-Utf8 (Join-Path $here $project.changelog_file)

# ---------------------------------------------------------------- token

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
if (-not $Token -and -not $DryRun) {
    throw 'No Modrinth token. Use -Token, set $env:MODRINTH_TOKEN, or write the token to .build-tools\modrinth\token.txt'
}

Write-Host '== Modrinth publish =='
Write-Host "root     : $Root"
Write-Host "project  : $($project.slug) ($($project.title))"
Write-Host "versions : $($versions.Count)"
if ($DryRun) { Write-Host 'mode     : DRY RUN - nothing will be uploaded' }

# ---------------------------------------------------------------- plan

$plan = @()
foreach ($v in $versions) {
    $jarPath = Join-Path $Root ($v.file -replace '/', '\')
    $srcPath = $null
    if ($v.sources) { $srcPath = Join-Path $Root ($v.sources -replace '/', '\') }
    $jarOk = Test-Path -LiteralPath $jarPath
    $srcOk = ($srcPath -and (Test-Path -LiteralPath $srcPath))
    $sizeMb = 0
    if ($jarOk) { $sizeMb = [math]::Round((Get-Item -LiteralPath $jarPath).Length / 1MB, 2) }
    $plan += [pscustomobject]@{
        Version = $v.version_number
        Mc      = ($v.game_versions -join ',')
        Jar     = $jarPath
        JarOk   = $jarOk
        SizeMb  = $sizeMb
        Sources = $srcOk
    }
}
$plan | Format-Table -AutoSize | Out-String | Write-Host

$missing = @($plan | Where-Object { -not $_.JarOk })
if ($missing.Count -gt 0) { throw "missing jar file(s): $(($missing | ForEach-Object { $_.Jar }) -join '; ')" }

if ($DryRun) {
    Write-Host '--- project payload (create) ---'
    $preview = [ordered]@{
        slug                  = $project.slug
        title                 = $project.title
        description           = $project.description
        categories            = @($project.categories)
        additional_categories = @($project.additional_categories)
        project_type          = $project.project_type
        status                = $project.status
        license_id            = $project.license_id
        client_side           = $project.client_side
        server_side           = $project.server_side
        source_url            = $project.source_url
        issues_url            = $project.issues_url
        wiki_url              = $project.wiki_url
        body                  = "<$($bodyText.Length) characters from $($project.body_file)>"
        icon                  = $project.icon_file
    }
    Write-Host (($preview | ConvertTo-Json -Depth 6))
    Write-Host '--- version payloads ---'
    foreach ($v in $versions) {
        Write-Host ("  {0,-16} mc={1,-8} loaders={2} type={3} env={4}" -f $v.version_number, ($v.game_versions -join ','), ($v.loaders -join ','), $v.version_type, $v.environment)
    }
    Write-Host '--- target ---'
    Write-Host "  https://modrinth.com/mod/$($project.slug)"
    Write-Host 'DRY RUN finished - nothing was uploaded.'
    return
}

# ---------------------------------------------------------------- client

$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromMinutes(60)
[void]$client.DefaultRequestHeaders.TryAddWithoutValidation('Authorization', $Token)
[void]$client.DefaultRequestHeaders.TryAddWithoutValidation('User-Agent', $ua)

try {
    $user = Invoke-Api -Method 'Get' -Path '/user'
    Write-Host "token    : $($user.username) (id $($user.id))"
} catch {
    throw "the token was rejected by /user ($(Get-ErrorText $_)). A personal access token with PROJECT_CREATE, PROJECT_WRITE and VERSION_CREATE is required."
}

# ---------------------------------------------------------------- project

$existing = $null
try { $existing = Invoke-Api -Method 'Get' -Path "/project/$($project.slug)" } catch { $existing = $null }

$projectBody = [ordered]@{
    title                 = $project.title
    description           = $project.description
    body                  = $bodyText
    categories            = @($project.categories)
    additional_categories = @($project.additional_categories)
    status                = $project.status
    license_id            = $project.license_id
    source_url            = $project.source_url
    issues_url            = $project.issues_url
    wiki_url              = $project.wiki_url
    client_side           = $project.client_side
    server_side           = $project.server_side
}
$iconPath = Join-Path $here $project.icon_file

if (-not $existing) {
    $createBody = [ordered]@{ slug = $project.slug; project_type = $project.project_type }
    foreach ($key in $projectBody.Keys) { $createBody[$key] = $projectBody[$key] }
    Write-Host "creating project $($project.slug) ..."
    $result = Send-Multipart -Client $client -Path '/project' -Json (To-JsonText $createBody) -Files @{ icon = $iconPath }
    if ($result.Status -lt 200 -or $result.Status -ge 300) {
        throw "project creation failed (HTTP $($result.Status)): $($result.Text)"
    }
    $created = $result.Text | ConvertFrom-Json
    $projectId = $created.id
    Write-Host "created  : $($created.slug) ($projectId)"
} else {
    $projectId = $existing.id
    Write-Host "project exists: $($existing.slug) ($projectId) - $($existing.versions.Count) version(s)"
    if (-not $NoProjectUpdate) {
        Write-Host 'updating project fields and body ...'
        try {
            $patched = Invoke-Api -Method 'Patch' -Path "/project/$projectId" -Json (To-JsonText $projectBody)
            Write-Host "updated  : $($patched.slug)"
        } catch {
            Write-Host "full update failed ($(Get-ErrorText $_)) - retrying with title/description/body only"
            $minimal = [ordered]@{ title = $project.title; description = $project.description; body = $bodyText }
            $patched = Invoke-Api -Method 'Patch' -Path "/project/$projectId" -Json (To-JsonText $minimal)
            Write-Host "updated  : $($patched.slug)"
        }
        $icon = Send-MultipartPatch -Client $client -Path "/project/$projectId/icon" -Files @{ icon = $iconPath }
        if ($icon.Status -ge 200 -and $icon.Status -lt 300) { Write-Host 'icon     : updated' } else { Write-Host "icon     : skipped (HTTP $($icon.Status))" }
    }
}

# ---------------------------------------------------------------- dependencies

$dependencies = @()
foreach ($slug in @($project.dependency_slugs)) {
    if (-not $slug) { continue }
    try {
        $dep = Invoke-Api -Method 'Get' -Path "/project/$slug"
        $dependencies += [ordered]@{ project_id = $dep.id; dependency_type = 'required' }
        Write-Host "dependency: $slug ($($dep.id)) required"
    } catch {
        Write-Host "dependency: $slug could not be resolved ($(Get-ErrorText $_)) - skipping"
    }
}

# ---------------------------------------------------------------- versions

$published = @()
try {
    $published = @(Invoke-Api -Method 'Get' -Path "/project/$projectId/version")
} catch {
    Write-Host "could not list existing versions ($(Get-ErrorText $_)) - continuing"
}
$publishedNumbers = @($published | ForEach-Object { $_.version_number })

$summary = @()
foreach ($v in $versions) {
    if (($publishedNumbers -contains $v.version_number) -and -not $Force) {
        Write-Host "skip     : $($v.version_number) is already published"
        $summary += [pscustomobject]@{ Version = $v.version_number; Result = 'already published' }
        continue
    }

    $jarPath = Join-Path $Root ($v.file -replace '/', '\')
    $files = @{ file = $jarPath }
    $payload = [ordered]@{
        name           = $v.name
        version_number = $v.version_number
        changelog      = $changelogText
        game_versions  = @($v.game_versions)
        loaders        = @($v.loaders)
        version_type   = $v.version_type
        project_id     = $projectId
        file_parts     = @('file')
        primary_file   = 'file'
        status         = 'listed'
    }
    if ($v.environment) { $payload['environment'] = $v.environment }
    if ($dependencies.Count -gt 0) { $payload['dependencies'] = $dependencies }
    if ($v.sources) {
        $srcPath = Join-Path $Root ($v.sources -replace '/', '\')
        if (Test-Path -LiteralPath $srcPath) {
            $files['sources'] = $srcPath
            $payload['file_parts'] = @('file', 'sources')
            $payload['file_types'] = [ordered]@{ sources = 'sources-jar' }
        }
    }

    Write-Host "uploading: $($v.version_number) <- $(Split-Path -Leaf $jarPath)"
    $result = Send-Multipart -Client $client -Path '/version' -Json (To-JsonText $payload) -Files $files
    if ($result.Status -lt 200 -or $result.Status -ge 300) {
        Write-Host "  HTTP $($result.Status): $($result.Text)"
        if ($payload['environment']) {
            Write-Host '  retrying without the environment field ...'
            $payload.Remove('environment')
            $result = Send-Multipart -Client $client -Path '/version' -Json (To-JsonText $payload) -Files $files
        }
    }
    if ($result.Status -lt 200 -or $result.Status -ge 300) {
        Write-Host "  FAILED (HTTP $($result.Status)): $($result.Text)"
        $summary += [pscustomobject]@{ Version = $v.version_number; Result = "failed HTTP $($result.Status)" }
        continue
    }
    $publishedVersion = $result.Text | ConvertFrom-Json
    Write-Host "  ok       : $($publishedVersion.id)"
    $summary += [pscustomobject]@{ Version = $v.version_number; Result = 'published' }
}

# ---------------------------------------------------------------- report

Write-Host ''
Write-Host '== summary =='
$summary | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($summary | Where-Object { $_.Result -like 'failed*' })
Write-Host "project page: https://modrinth.com/mod/$($project.slug)"
if ($failed.Count -gt 0) { exit 1 }
Write-Host 'PUBLISH_DONE'
