# Publishes the three GitHub releases for one mod version: Minecraft 1.21 (flagship), 1.21.11
# and 26.1.2. This is the generic successor of the one-off release-v190 / release-v191 /
# release-v192 / release-v181-mc scripts, which stay in the tree only as historical records
# (each refuses to run without -AllowHistoricalRerun).
#
# Usage for the next release:
#
#   .build-tools\release.ps1 -Version 1.9.4 -PreviousTag v1.9.3
#
# A read-only rehearsal that touches nothing:
#
#   .build-tools\release.ps1 -Version 1.9.3 -DryRun
#
# -Version is the mod version that is compiled into the jars. Tags, release titles, asset
# names and the notes file names are all derived from it (v1.9.4, v1.9.4-mc1.21.11,
# v1.9.4-mc26.1.2, release-v194-1.21.md, ...), and every derived value is cross-checked
# against the version that is actually inside the jar, so this script can no longer push
# today's build/libs over a release that belongs to another version.
#
# -PreviousTag is the flagship tag of the superseded release; the per-tree variants
# (v1.9.3-mc1.21.11, v1.9.3-mc26.1.2) are derived from it. Without it the "previous release
# now points at this one" step is skipped.
#
# Before running: bump-version.ps1, rebuild all three trees, generate the per-tree notes with
# make-notes-<digits>.ps1 and commit everything. The script refuses a dirty worktree.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$PreviousTag = '',
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if ($Version -notmatch '^[0-9]+(\.[0-9]+)*$') { throw "invalid -Version '$Version' (expected e.g. 1.9.4)" }
$tagBase = "v$Version"
$digits = $Version -replace '\.', ''
$notesBase = 'release-v' + $digits
$notesScript = 'make-notes-' + $digits + '.ps1'

# One jar reader for the whole toolchain (version + declared minecraft versions).
. (Join-Path $PSScriptRoot 'lib\jar-manifest.ps1')

$targets = @(
    @{ ver = '1.21';    tag = "$tagBase";           jar = "coreprotect-fabric-1.21\build\libs\coreprotect-fabric-1.21-$Version.jar";         notes = "$notesBase-1.21.md" },
    @{ ver = '1.21.11'; tag = "$tagBase-mc1.21.11"; jar = "coreprotect-fabric-1.21.11\build\libs\coreprotect-fabric-1.21.11-$Version.jar"; notes = "$notesBase-1.21.11.md" },
    @{ ver = '26.1.2';  tag = "$tagBase-mc26.1.2";  jar = "coreprotect-fabric-26.1.2\build\libs\coreprotect-fabric-26.1.2-$Version.jar";   notes = "$notesBase-26.1.2.md" }
)

# --- preflight: every check below happens before GitHub is touched -----------------------
# gradle.properties, the jar itself, the version compiled into it, its SHA-256 and the notes
# file are all verified here, so a broken release is refused instead of half-published.
Write-Output "preflight for $tagBase"
$missing = @()
foreach ($t in $targets) {
    $gpPath = Join-Path $root "coreprotect-fabric-$($t.ver)\gradle.properties"
    if (-not (Test-Path -LiteralPath $gpPath)) { throw "coreprotect-fabric-$($t.ver): gradle.properties is missing" }
    $declared = ((Select-String -LiteralPath $gpPath -Pattern '^mod_version=(.*)$').Matches[0].Groups[1].Value).Trim()
    if ($declared -ne $Version) { throw "coreprotect-fabric-$($t.ver): gradle.properties has mod_version=$declared, but -Version is $Version" }
    $jarPath = Join-Path $root $t.jar
    if (-not (Test-Path -LiteralPath $jarPath)) { $missing += $jarPath; continue }
    $info = Get-JarModInfo $jarPath
    if ($info.Version -ne $Version) {
        throw "$($t.jar) contains fabric.mod.json version '$($info.Version)', expected '$Version' - rebuild that tree before releasing"
    }
    $sha = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
    $mcText = if ($info.Minecraft.Count -gt 0) { $info.Minecraft -join ',' } else { '(none)' }
    Write-Output ("  {0,-7} {1,8:N2} MB  mc {2,-9} sha256 {3}" -f $t.ver, ((Get-Item -LiteralPath $jarPath).Length / 1MB), $mcText, $sha)
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $t.notes))) {
        throw "release notes $($t.notes) are missing - run .build-tools\$notesScript first"
    }
}
if ($missing.Count -gt 0) {
    Write-Output 'MISSING JAR:'
    $missing | ForEach-Object { Write-Output "  $_" }
    throw "refusing to publish: $($missing.Count) of $($targets.Count) jars are missing and the release would look complete without them"
}

if ($DryRun) {
    Write-Output ''
    Write-Output 'dry run plan:'
    foreach ($t in $targets) {
        $prev = if (-not $PreviousTag) { '(none)' } elseif ($t.ver -eq '1.21') { $PreviousTag } else { "$PreviousTag-mc$($t.ver)" }
        Write-Output ("  {0,-18} jar {1}" -f $t.tag, ([System.IO.Path]::GetFileName($t.jar)))
        Write-Output ("  {0,-18} notes {1}" -f '', $t.notes)
        Write-Output ("  {0,-18} back-link {1}" -f '', $prev)
    }
    Write-Output 'DRY RUN finished - nothing was published.'
    return
}

# The release has to be reproducible from what is actually committed: a dirty worktree means
# the published jar was not built from any commit that exists on GitHub. Untracked files are
# ignored here (--untracked-files=no): scratch directories and probe output never enter a jar.
$dirty = @(& git -C $root status --porcelain --untracked-files=no)
if ($dirty.Count -gt 0) {
    $dirty | ForEach-Object { Write-Output "  $_" }
    throw 'tracked files have uncommitted changes - commit the release before publishing'
}
$head = (& git -C $root rev-parse --short HEAD)
Write-Output "worktree clean at $head; publishing $tagBase"

# Non-ASCII text lives in UTF-8 data files: Windows PowerShell reads .ps1 files as ANSI.
$supersededText = ''
if ($PreviousTag) {
    $supersededPath = Join-Path $PSScriptRoot 'repair\superseded.txt'
    if (-not (Test-Path -LiteralPath $supersededPath)) { throw "repair\superseded.txt is missing but -PreviousTag was given" }
    $supersededText = ([System.IO.File]::ReadAllText($supersededPath, [System.Text.Encoding]::UTF8)).TrimEnd("`r", "`n")
}

# Read the credential through a real stdin pipe: parking the PAT in a temp file and cleaning it
# up best-effort left a token on disk. Windows PowerShell 5.1 has no `<` redirection, and piping
# the request into `git credential fill` does not give it a usable stdin (it answers "refusing
# to work with credential missing protocol field"), hence the one-line request file that is
# deleted again immediately.
$credFile = Join-Path $env:TEMP ("ghcred-in-" + [System.Guid]::NewGuid().ToString('N') + ".txt")
[System.IO.File]::WriteAllText($credFile, "protocol=https`nhost=github.com`n")
try {
    $cred = & cmd /c "git credential fill < `"$credFile`" 2>NUL"
} finally {
    Remove-Item -LiteralPath $credFile -Force -ErrorAction SilentlyContinue
}
$tokenLine = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
if (-not $tokenLine) { throw 'no github credential available from git credential fill' }
$headers = @{ 'Authorization' = "Bearer $($tokenLine.Substring(9).Trim())"; 'User-Agent' = 'coreprotect-release' }
$repo = 'CNTianCheng/coreprotect-fabric'

foreach ($t in $targets) {
    $jarPath = Join-Path $root $t.jar
    $notesPath = Join-Path $PSScriptRoot $t.notes
    $body = [System.IO.File]::ReadAllText($notesPath, [System.Text.Encoding]::UTF8)
    $name = "CoreProtect Fabric v$Version (Minecraft $($t.ver))"

    $existing = $null
    try { $existing = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$($t.tag)" -Headers $headers } catch { $existing = $null }

    if ($existing) {
        $patch = @{ body = $body; name = $name } | ConvertTo-Json -Depth 4
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$($existing.id)" -Headers $headers -Method Patch -Body ([System.Text.Encoding]::UTF8.GetBytes($patch)) -ContentType 'application/json; charset=utf-8'
        Write-Output "updated release $($t.tag) (id $($rel.id))"
    } else {
        # Created as a draft and only published once the jar is uploaded: if anything fails in
        # between, the result is an invisible draft instead of a public release with no jar.
        $payload = @{ tag_name = $t.tag; target_commitish = 'main'; name = $name; body = $body; draft = $true; prerelease = $false } | ConvertTo-Json -Depth 4
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Headers $headers -Method Post -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) -ContentType 'application/json; charset=utf-8'
        Write-Output "created draft release $($t.tag) (id $($rel.id))"
    }

    $assetName = [System.IO.Path]::GetFileName($jarPath)
    $already = @($rel.assets | Where-Object { $_.name -eq $assetName })
    $bytes = [System.IO.File]::ReadAllBytes($jarPath)
    # Upload the replacement BEFORE deleting the old asset. GitHub allows two assets to carry
    # the same name, so a failed upload leaves the release with its previous jar instead of
    # no jar at all.
    $up = Invoke-RestMethod -Uri "https://uploads.github.com/repos/$repo/releases/$($rel.id)/assets?name=$assetName" -Headers $headers -Method Post -Body $bytes -ContentType 'application/java-archive'
    Write-Output ("  uploaded {0} ({1} MB)" -f $up.name, [math]::Round($up.size / 1MB, 1))
    foreach ($old in $already) {
        if ($old.id -eq $up.id) { continue }
        try {
            Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/assets/$($old.id)" -Headers $headers -Method Delete | Out-Null
            Write-Output "  removed superseded asset $assetName (id $($old.id))"
        } catch {
            Write-Output "  (old asset $($old.id) could not be deleted: $($_.Exception.Message))"
        }
    }

    $patch = @{ draft = $false } | ConvertTo-Json -Depth 4
    Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$($rel.id)" -Headers $headers -Method Patch -Body ([System.Text.Encoding]::UTF8.GetBytes($patch)) -ContentType 'application/json; charset=utf-8' | Out-Null
    Write-Output "  live: $($rel.html_url)"

    if (-not $PreviousTag) {
        Write-Output '  (no -PreviousTag given: the previous release was not updated)'
        continue
    }
    $prevTag = if ($t.ver -eq '1.21') { $PreviousTag } else { "$PreviousTag-mc$($t.ver)" }
    try {
        $prevRel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$prevTag" -Headers $headers
        $prefix = $supersededText.Replace('@TAG@', $t.tag) + "`n`n"
        if ($prevRel.body -notlike "*$($t.tag)*") {
            $patch = @{ body = $prefix + $prevRel.body } | ConvertTo-Json -Depth 4
            Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$($prevRel.id)" -Headers $headers -Method Patch -Body ([System.Text.Encoding]::UTF8.GetBytes($patch)) -ContentType 'application/json; charset=utf-8' | Out-Null
            Write-Output "  noted on previous release $prevTag"
        } else {
            Write-Output "  previous release $prevTag already mentions $($t.tag)"
        }
    } catch {
        Write-Output "  (previous release $prevTag not updated: $($_.Exception.Message))"
    }
}
Write-Output 'publish done'
