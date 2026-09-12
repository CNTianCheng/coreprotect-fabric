$ErrorActionPreference = 'Stop'
# Windows PowerShell reads .ps1 files without a BOM as ANSI, so any non-ASCII literal
# inside a script becomes mojibake (this already corrupted release bodies once).
# Every string that must stay readable has to live in a UTF-8 data file instead.
$root = Split-Path $PSScriptRoot -Parent
$files = Get-ChildItem -Path $PSScriptRoot -Recurse -Filter '*.ps1'
$offenders = @()
foreach ($f in $files) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    $bad = 0
    foreach ($b in $bytes) { if ($b -gt 127) { $bad++ } }
    if ($bad -gt 0) {
        $rel = $f.FullName.Substring($root.Length + 1)
        $offenders += ("{0} ({1} non-ASCII bytes)" -f $rel, $bad)
    }
}
if ($offenders.Count -eq 0) {
    Write-Output "OK: every .ps1 under .build-tools is pure ASCII ($($files.Count) files)."
} else {
    Write-Output "NON-ASCII FOUND - move these literals into a UTF-8 data file:"
    $offenders | ForEach-Object { Write-Output "  $_" }
}
