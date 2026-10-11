# Adds the coreprotect.error.busy key to every language file of the three trees.
# The translations live in lang-busy.tsv (UTF-8); this script stays pure ASCII.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$tsv = Join-Path $PSScriptRoot 'lang-busy.tsv'
$lines = [System.IO.File]::ReadAllLines($tsv, (New-Object System.Text.UTF8Encoding($false)))
$map = @{}
foreach ($line in $lines) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split "`t", 2
    if ($parts.Count -ne 2) { throw "Bad TSV line: $line" }
    $map[$parts[0]] = $parts[1]
}
$trees = @('coreprotect-fabric-1.21', 'coreprotect-fabric-1.21.11', 'coreprotect-fabric-26.1.2')
$utf8 = New-Object System.Text.UTF8Encoding($false)
$changed = 0
foreach ($tree in $trees) {
    $dir = Join-Path $root "$tree\src\main\resources\assets\coreprotect\lang"
    foreach ($code in $map.Keys) {
        $file = Join-Path $dir "$code.json"
        if (-not (Test-Path $file)) { throw "Missing language file: $file" }
        $text = [System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)
        if ($text.Contains('coreprotect.error.busy')) {
            Write-Host "skip (already present): $file"
            continue
        }
        $anchor = '"coreprotect.undo.empty"'
        $index = $text.IndexOf($anchor)
        if ($index -lt 0) { throw "No anchor in $file" }
        $eol = $text.IndexOf("`n", $index)
        if ($eol -lt 0) { throw "No end of line after the anchor in $file" }
        $entry = '  "coreprotect.error.busy": "' + $map[$code] + '",' + "`r`n"
        $text = $text.Substring(0, $eol + 1) + $entry + $text.Substring($eol + 1)
        [System.IO.File]::WriteAllText($file, $text, $utf8)
        $changed++
        Write-Host "added: $file"
    }
}
Write-Host "files changed: $changed"
