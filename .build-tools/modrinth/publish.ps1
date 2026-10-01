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

    The project already exists and was created by the repository owner, who owns its
    icon, description and body. Nothing about the project is modified unless
    -UpdateProject is passed, and the icon is NEVER replaced unless -SetIcon is
    passed explicitly - uploading versions is the default and only intended action.

    Windows PowerShell 5.1 has no -Form on Invoke-RestMethod, hence the HttpClient
    multipart uploads below.
#>
[CmdletBinding()]
param(
    [string]$Token,
    [string]$Root,
    [switch]$DryRun,
    [switch]$UpdateProject,
    # With -UpdateProject: send only title, description and body, so the categories,
    # license and links the owner set on modrinth.com are left exactly as they are.
    [switch]$TitleBodyOnly,
    [switch]$SetIcon,
    [switch]$DumpPayload,
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

# Upload one multipart request: a `data` JSON field plus one file field per $Files entry.
#
# This deliberately recreates the exact request shape that was verified to upload
# successfully (probe6.ps1): a fresh HttpClient per request, the data part added first,
# no explicit Content-Type on the file parts, and the response disposed. The earlier
# shape (shared client, explicit application/octet-stream) made api.modrinth.com answer
# every upload of a real project with the actix framework's plain-text HTTP 405
# "Request did not meet this resource's requirements." instead of creating the version,
# even though the identical payload uploaded fine through this shape. Do not "simplify"
# it back without re-testing a real upload.
function Send-Multipart {
    param(
        [string]$Path,
        [string]$Json,
        [hashtable]$Files
    )
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(60)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('Authorization', $Token)
    [void]$client.DefaultRequestHeaders.TryAddWithoutValidation('User-Agent', $ua)
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
            $form.Add($content, $field, [System.IO.Path]::GetFileName($path))
        }
        $response = $client.PostAsync("$api$Path", $form).Result
        $text = $response.Content.ReadAsStringAsync().Result
        $status = [int]$response.StatusCode
        $response.Dispose()
        return @{ Status = $status; Text = $text }
    } finally {
        foreach ($stream in $streams) { $stream.Dispose() }
        $form.Dispose()
        $client.Dispose()
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
    Write-Host "token    : the /user check failed ($(Get-ErrorText $_)) - continuing anyway, the write calls below report real scope problems"
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
    $result = Send-Multipart -Path '/project' -Json (To-JsonText $createBody) -Files @{ icon = $iconPath }
    if ($result.Status -lt 200 -or $result.Status -ge 300) {
        throw "project creation failed (HTTP $($result.Status)): $($result.Text)"
    }
    $created = $result.Text | ConvertFrom-Json
    $projectId = $created.id
    Write-Host "created  : $($created.slug) ($projectId)"
} else {
    $projectId = $existing.id
    Write-Host "project exists: $($existing.slug) ($projectId) - $($existing.versions.Count) version(s)"
    if ($UpdateProject) {
        Write-Host 'updating project fields and body ...'
        $updateBody = $projectBody
        if ($TitleBodyOnly) {
            Write-Host 'scope    : title, description and body only (categories, license and links left as the owner set them)'
            $updateBody = [ordered]@{ title = $project.title; description = $project.description; body = $bodyText }
        }
        try {
            $patched = Invoke-Api -Method 'Patch' -Path "/project/$projectId" -Json (To-JsonText $updateBody)
            # A PATCH answers with a partial project object that may omit the slug, so
            # report the slug we asked for rather than whatever came back.
            Write-Host "updated  : $($project.slug)"
        } catch {
            Write-Host "full update failed ($(Get-ErrorText $_)) - retrying with title/description/body only"
            $minimal = [ordered]@{ title = $project.title; description = $project.description; body = $bodyText }
            $patched = Invoke-Api -Method 'Patch' -Path "/project/$projectId" -Json (To-JsonText $minimal)
            Write-Host "updated  : $($patched.slug)"
        }
    } else {
        Write-Host 'project  : left untouched (description, body, categories, icon)'
    }
    if ($SetIcon) {
        $icon = Send-MultipartPatch -Client $client -Path "/project/$projectId/icon" -Files @{ icon = $iconPath }
        if ($icon.Status -ge 200 -and $icon.Status -lt 300) { Write-Host 'icon     : updated' } else { Write-Host "icon     : skipped (HTTP $($icon.Status))" }
    } else {
        Write-Host 'icon     : left untouched (the owner set it on modrinth.com)'
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

# All three maintained builds share one version_number (1.9.0) and differ only by game
# version, so "already published" has to key on version_number AND game_versions -
# matching on the number alone would skip every build after the first one.
$publishedKeys = @{}
foreach ($p in $published) {
    $games = (@($p.game_versions) | Sort-Object) -join ','
    $publishedKeys["$($p.version_number)|$games"] = $p.id
}

$summary = @()
$dumpIndex = 1
foreach ($v in $versions) {
    $wantGames = (@($v.game_versions) | Sort-Object) -join ','
    $key = "$($v.version_number)|$wantGames"
    if ($publishedKeys.ContainsKey($key) -and -not $Force) {
        Write-Host "skip     : $($v.version_number) for $(($v.game_versions -join ',')) is already published ($($publishedKeys[$key]))"
        $summary += [pscustomobject]@{ Version = $v.version_number; Result = 'already published' }
        continue
    }

    $jarPath = Join-Path $Root ($v.file -replace '/', '\')
    $files = @{ file = $jarPath }
    # Payload contract, established empirically against api.modrinth.com (the docs
    # are behind the deployed API here):
    #   * The API wants version_title, NOT name, and answers 400 "missing field" for
    #     each of version_title, dependencies and featured that is absent.
    #   * dependencies must be present even when it is empty, and featured is required.
    #   * Sending status or environment makes the deployed API reply with the actix
    #     framework's plain-text HTTP 405 "Request did not meet this resource's
    #     requirements." (no labrinth JSON envelope) and no version is created. Both
    #     are simply omitted: the server then applies the same defaults the project
    #     owner already uses - status "listed" and environment "dedicated_server_only".
    #   * game_versions/loaders/file_parts must serialize as JSON arrays; PowerShell
    #     unrolls one-element arrays, so they are always re-wrapped with @(...).
    $payload = [ordered]@{
        version_title  = $v.name
        version_number = $v.version_number
        changelog      = $changelogText
        game_versions  = @($v.game_versions)
        loaders        = @($v.loaders)
        version_type   = $v.version_type
        project_id     = $projectId
        file_parts     = @('file')
        primary_file   = 'file'
        featured       = $false
        dependencies   = $dependencies
    }
    if ($v.sources) {
        $srcPath = Join-Path $Root ($v.sources -replace '/', '\')
        if (Test-Path -LiteralPath $srcPath) {
            $files['sources'] = $srcPath
            $payload['file_parts'] = @('file', 'sources')
            $payload['file_types'] = [ordered]@{ sources = 'sources-jar' }
        }
    }

    if ($DumpPayload) {
        $dumpPath = Join-Path $here ("payload-{0}.json" -f $dumpIndex)
        [System.IO.File]::WriteAllText($dumpPath, (To-JsonText $payload), (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "dumped   : $dumpPath ($((To-JsonText $payload).Length) chars)"
        $dumpIndex++
        continue
    }

    Write-Host "uploading: $($v.version_number) <- $(Split-Path -Leaf $jarPath)"
    $result = Send-Multipart -Path '/version' -Json (To-JsonText $payload) -Files $files
    if ($result.Status -lt 200 -or $result.Status -ge 300) {
        Write-Host "  HTTP $($result.Status): $($result.Text)"
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

if ($DumpPayload) {
    Write-Host 'PAYLOAD_DUMP_DONE'
    return
}

# ---------------------------------------------------------------- report

Write-Host ''
Write-Host '== summary =='
$summary | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($summary | Where-Object { $_.Result -like 'failed*' })
Write-Host "project page: https://modrinth.com/mod/$($project.slug)"
if ($failed.Count -gt 0) { exit 1 }
Write-Host 'PUBLISH_DONE'
