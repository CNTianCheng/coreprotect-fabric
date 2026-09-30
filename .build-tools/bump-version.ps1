# Bumps the three full-feature builds to a new version (ASCII-only replacements).
param([string]$From = '1.8.2', [string]$To = '1.9.0')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$utf8 = New-Object System.Text.UTF8Encoding($false)
$dirs = @('coreprotect-fabric-1.21', 'coreprotect-fabric-1.21.11', 'coreprotect-fabric-26.1.2')
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
        if (-not (Test-Path $f)) { Write-Output "MISSING $f"; continue }
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
Write-Output '=== remaining old version references ==='
foreach ($d in $dirs) {
    Get-ChildItem (Join-Path $root $d) -Recurse -File -Include '*.md', '*.java', '*.properties' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\build\\|\\.gradle\\|\\run\\' } |
        Select-String -Pattern ([regex]::Escape($From)) |
        ForEach-Object { "$($_.Path.Replace("$root\", '')):$($_.LineNumber)" }
}
Write-Output 'VERSION_BUMP_DONE'
