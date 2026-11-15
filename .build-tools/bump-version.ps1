# Bumps the three full-feature builds - and the repository-root docs - to a new version
# (ASCII-only replacements; no non-ASCII literals, so check-ascii.ps1 stays happy).
# Both versions are required: the old defaults (1.8.2 -> 1.9.0) stayed behind after every
# later release, and a missing file only printed "MISSING" while the script still ended
# with VERSION_BUMP_DONE - a half-applied bump looked like a successful one.
#
# The repository root is part of the bump (audit L5-04). It used to be skipped, so README.md /
# OVERVIEW.md / OVERVIEW_EN.md at the root kept the previous version for a whole release cycle -
# and Modrinth's wiki_url points at that README. Only those three files are touched at the root,
# never the whole tree.
param(
    [Parameter(Mandatory = $true)][string]$From,
    [Parameter(Mandatory = $true)][string]$To
)
$ErrorActionPreference = 'Stop'
if ($From -eq $To) { throw "-From and -To are both '$From'" }
$root = Split-Path $PSScriptRoot -Parent
$utf8 = New-Object System.Text.UTF8Encoding($false)
# '.' is the repository root; it uses its own file list below.
$dirs = @('.', 'coreprotect-fabric-1.21', 'coreprotect-fabric-1.21.11', 'coreprotect-fabric-26.1.2')
$treeFiles = @(
    'src\main\java\net\coreprotect\fabric\CoreProtectFabric.java',
    'gradle.properties',
    'README.md',
    'OVERVIEW.md',
    'OVERVIEW_EN.md'
)
$rootFiles = @('README.md', 'OVERVIEW.md', 'OVERVIEW_EN.md')
# A version token in the root docs is not preceded by a digit/dot and not followed by a digit,
# so the pattern cannot eat into 11.9.3 or 1.9.30, and it does not have to spell out the wide
# parentheses the Chinese matrix puts around the flagship marker (this script must stay ASCII).
$verPattern = '(?<![0-9.])' + [regex]::Escape($From) + '(?![0-9])'
$missing = @()
foreach ($d in $dirs) {
    $base = Join-Path $root $d
    $isRoot = ($d -eq '.')
    $relFiles = if ($isRoot) { $rootFiles } else { $treeFiles }
    foreach ($rf in $relFiles) {
        $f = Join-Path $base $rf
        if (-not (Test-Path $f)) { Write-Output "MISSING $f"; $missing += $f; continue }
        $t = [System.IO.File]::ReadAllText($f, $utf8)
        if ($isRoot) {
            # Replace line by line: a table row starting with "| v1.9.1" is the changelog of an
            # older release and must keep its own number. Rebuilding the text with the same
            # separator makes this lossless for both LF and CRLF files.
            $docLines = @($t -split "`n" | ForEach-Object {
                if ($_ -match '^\s*\|\s*v[0-9]') { $_ } else { [regex]::Replace($_, $verPattern, $To) }
            })
            $n = $docLines -join "`n"
        } else {
            $n = $t.Replace("MOD_VERSION = `"$From`"", "MOD_VERSION = `"$To`"").
                Replace("mod_version=$From", "mod_version=$To").
                Replace("-$From.jar", "-$To.jar").
                Replace("v$From", "v$To")
        }
        if ($n -ne $t) {
            [System.IO.File]::WriteAllText($f, $n, $utf8)
            Write-Output ("updated {0}" -f $f.Replace("$root\", ''))
        }
    }
}
# versions.json repeats the mod version for Modrinth and used to be forgotten here, which
# left the next publish either reporting a missing jar or uploading the previous build as
# the new version. Keep it in step in the same run.
$vp = Join-Path $PSScriptRoot 'modrinth\versions.json'
if (-not (Test-Path $vp)) {
    Write-Output "MISSING $vp"
    $missing += $vp
} else {
    $t = [System.IO.File]::ReadAllText($vp, $utf8)
    $n = $t.Replace("CoreProtect-Fabric $From", "CoreProtect-Fabric $To").
        Replace("`"version_number`": `"$From`"", "`"version_number`": `"$To`"").
        Replace("-$From.jar", "-$To.jar")
    if ($n -ne $t) {
        [System.IO.File]::WriteAllText($vp, $n, $utf8)
        Write-Output ("updated {0}" -f $vp.Replace("$root\", ''))
    }
    $stale = @([regex]::Matches($n, '"version_number":\s*"([^"]+)"') | Where-Object { $_.Groups[1].Value -ne $To })
    if ($stale.Count -gt 0) {
        $found = ($stale | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique) -join ', '
        throw "modrinth\versions.json still says version_number=$found (expected $To) - fix it by hand before publishing"
    }
}
if ($missing.Count -gt 0) {
    Write-Output '=== files that were NOT bumped ==='
    $missing | ForEach-Object { Write-Output "  $_" }
    throw "version bump incomplete: $($missing.Count) expected file(s) not found"
}
Write-Output '=== remaining old version references ==='
foreach ($d in $dirs) {
    if ($d -eq '.') {
        # Only the three root docs are bumped above; recursing over the repository root would
        # scan every tree a second time and report unrelated build output.
        foreach ($rf in $rootFiles) {
            Select-String -LiteralPath (Join-Path $root $rf) -Pattern ([regex]::Escape($From)) -ErrorAction SilentlyContinue |
                ForEach-Object { "$($_.Path.Replace("$root\", '')):$($_.LineNumber)" }
        }
        continue
    }
    Get-ChildItem (Join-Path $root $d) -Recurse -File -Include '*.md', '*.java', '*.properties' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\build\\|\\.gradle\\|\\run\\' } |
        Select-String -Pattern ([regex]::Escape($From)) |
        ForEach-Object { "$($_.Path.Replace("$root\", '')):$($_.LineNumber)" }
}
Write-Output 'VERSION_BUMP_DONE'
