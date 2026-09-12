$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$cfgPath = Join-Path $PSScriptRoot 'docs-fix.json'
$cfg = [System.IO.File]::ReadAllText($cfgPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$srcDocs = Join-Path $root 'coreprotect-fabric-1.21'
$docs = @('README.md', 'OVERVIEW.md', 'OVERVIEW_EN.md')

foreach ($t in $cfg.targets) {
    $ver = $t.version
    $dir = Join-Path $root $t.name
    Write-Host "--- $($t.name) ---"

    foreach ($d in $docs) {
        $text = [System.IO.File]::ReadAllText((Join-Path $srcDocs $d), [System.Text.Encoding]::UTF8)
        foreach ($p in $cfg.common_pairs) { $text = $text.Replace($p[0], $p[1].Replace('@VER@', $ver)) }
        foreach ($p in $cfg.line_pairs) { $text = $text.Replace($p[0], $p[1].Replace('@VER@', $ver)) }
        foreach ($p in $t.java_pairs) { $text = $text.Replace($p[0], $p[1]) }
        [System.IO.File]::WriteAllText((Join-Path $dir $d), $text, $utf8NoBom)
        Write-Host "  wrote $d"
    }

    # fix the corrupted fabric.mod.json description
    $fmjPath = Join-Path $dir 'src\main\resources\fabric.mod.json'
    $fmj = [System.IO.File]::ReadAllText($fmjPath, [System.Text.Encoding]::UTF8)
    $fmj = [regex]::Replace($fmj, '"description":\s*"(?:[^"\\]|\\.)*"', '"description": "' + $cfg.description + '"')
    [System.IO.File]::WriteAllText($fmjPath, $fmj, $utf8NoBom)
    Write-Host "  fixed fabric.mod.json description"
}
Write-Host 'docs update done'
