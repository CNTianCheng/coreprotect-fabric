# Bumps the three full-feature builds to a new version (ASCII-only replacements).
# Both versions are required: the old defaults (1.8.2 -> 1.9.0) stayed behind after every
# later release, and a missing file only printed "MISSING" while the script still ended
# with VERSION_BUMP_DONE - a half-applied bump looked like a successful one.
param(
    [Parameter(Mandatory = $true)][string]$From,
    [Parameter(Mandatory = $true)][string]$To
)
$ErrorActionPreference = 'Stop'
if ($From -eq $To) { throw "-From and -To are both '$From'" }
$root = Split-Path $PSScriptRoot -Parent
$utf8 = New-Object System.Text.UTF8Encoding($false)
$dirs = @('coreprotect-fabric-1.21', 'coreprotect-fabric-1.21.11', 'coreprotect-fabric-26.1.2')
$missing = @()
foreach ($d in $dirs) {
    $base = Join-Path $root $d
    $files = @(
        (Join-Path $base 'src\main\java\net\coreprotect\fabric\CoreProtectFabric.java'),
        (Join-Path $base 'gradle.properties'),
        (Join-Path $base 'README.md'),
        (Join-Path $base 'OVERVIEW.md'),
        (Join-Path $base 'OVERVIEW_EN.md')
    )
    foreach ($f in $files) {
        if (-not (Test-Path $f)) { Write-Output "MISSING $f"; $missing += $f; continue }
        $t = [System.IO.File]::ReadAllText($f, $utf8)
        $n = $t.Replace("MOD_VERSION = `"$From`"", "MOD_VERSION = `"$To`"").
            Replace("mod_version=$From", "mod_version=$To").
            Replace("-$From.jar", "-$To.jar").
            Replace("v$From", "v$To")
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
    Get-ChildItem (Join-Path $root $d) -Recurse -File -Include '*.md', '*.java', '*.properties' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\build\\|\\.gradle\\|\\run\\' } |
        Select-String -Pattern ([regex]::Escape($From)) |
        ForEach-Object { "$($_.Path.Replace("$root\", '')):$($_.LineNumber)" }
}
Write-Output 'VERSION_BUMP_DONE'
