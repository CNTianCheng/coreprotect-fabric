$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$cfg = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release-pairs.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$src = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release-v181.md'), [System.Text.Encoding]::UTF8)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

foreach ($t in $cfg.targets) {
    $ver = $t.version
    $text = $src
    foreach ($p in $cfg.pairs) {
        $repl = $p[1].Replace('@VER@', $ver).Replace('@JAVA@', $t.java).Replace('@LOADER@', $t.loader)
        $text = $text.Replace($p[0], $repl)
    }
    # insert the parity note right after each language heading
    $en = $cfg.note_en.Replace('@VER@', $ver)
    $zh = $cfg.note_zh.Replace('@VER@', $ver)
    $text = $text.Replace($cfg.anchor_en, $cfg.anchor_en + "`r`n`r`n" + $en)
    $text = $text.Replace($cfg.anchor_zh, $cfg.anchor_zh + "`r`n`r`n" + $zh)
    $out = Join-Path $PSScriptRoot ("release-v181-" + $ver + ".md")
    [System.IO.File]::WriteAllText($out, $text, $utf8NoBom)
    Write-Host "wrote $out"
}
