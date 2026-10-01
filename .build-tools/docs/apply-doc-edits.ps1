<#
    apply-doc-edits.ps1 -- apply the UTF-8 find/replace pairs listed in doc-edits.json
    to the repository's root documentation files.

    ASCII only (see .build-tools/check-ascii.ps1). All Chinese text lives in doc-edits.json
    and is read with an explicit UTF-8 decoder, because Windows PowerShell 5.1 would read a
    BOM-less script as ANSI. Each edit asserts how many times its find string occurs before
    anything is written, and every file is written back as BOM-less UTF-8, so a stale or
    mistyped pattern fails loudly instead of silently mangling a document.

    Usage:
        powershell -ExecutionPolicy Bypass -File .build-tools\docs\apply-doc-edits.ps1
        powershell -ExecutionPolicy Bypass -File .build-tools\docs\apply-doc-edits.ps1 -WhatIf
#>
[CmdletBinding()]
param(
    [string]$Root,
    # Name of the edit list next to this script (or an absolute path). Defaults to
    # doc-edits.json; pass another file to apply a separate batch of edits. Note that
    # this must NOT be called $Data: PowerShell variables are case-insensitive and this
    # script assigns the parsed JSON to $data, which a [string]-typed parameter would
    # silently coerce to its string representation.
    [string]$EditFile,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $Root) { $Root = (Resolve-Path (Join-Path $here '..\..')).Path }

$utf8 = New-Object System.Text.UTF8Encoding($false)
$strict = New-Object System.Text.UTF8Encoding($false, $true)

if (-not $EditFile) { $EditFile = 'doc-edits.json' }
$dataPath = if ([System.IO.Path]::IsPathRooted($EditFile)) { $EditFile } else { Join-Path $here $EditFile }
$data = [System.IO.File]::ReadAllText($dataPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json

Write-Host "== apply doc edits =="
Write-Host "root : $Root"
Write-Host "edits: $($data.edits.Count)"

$files = [ordered]@{}
$missing = 0
$applied = 0
$skipped = 0

foreach ($edit in $data.edits) {
    $path = Join-Path $Root ($edit.file -replace '/', '\')
    if (-not $files.Contains($path)) {
        try {
            $files[$path] = $strict.GetString([System.IO.File]::ReadAllBytes($path))
        } catch {
            throw "$($edit.file) is not valid UTF-8: $($_.Exception.Message)"
        }
    }
    $text = $files[$path]
    $found = ([regex]::Matches($text, [regex]::Escape($edit.find))).Count
    $label = "[{0}] {1} (find #{2})" -f $edit.file, $edit.count, 0
    if ($found -eq $edit.count) {
        $files[$path] = $text.Replace($edit.find, $edit.replace)
        Write-Host ("  ok      : {0}  x{1}" -f $edit.file, $found)
        $applied++
    } elseif ($found -eq 0) {
        Write-Host ("  missing : {0}  (pattern not found - already applied?)" -f $edit.file)
        $skipped++
    } else {
        Write-Host ("  MISMATCH: {0}  expected x{1}, found x{2}" -f $edit.file, $edit.count, $found)
        $missing++
    }
}

if ($missing -gt 0) {
    throw "$missing edit(s) matched an unexpected number of times - nothing was written."
}

$written = 0
foreach ($path in $files.Keys) {
    $original = $strict.GetString([System.IO.File]::ReadAllBytes($path))
    if ($original -eq $files[$path]) { continue }
    if ($WhatIf) {
        Write-Host "  whatif  : $path"
        continue
    }
    if (-not $path.StartsWith($Root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "refusing to write outside the repository root: $path"
    }
    [System.IO.File]::WriteAllText($path, $files[$path], $utf8)
    $written++
    Write-Host "  wrote   : $($path.Substring($Root.Length + 1))"
}

Write-Host ("applied={0} skipped={1} files-written={2}" -f $applied, $skipped, $written)
Write-Host 'DOC_EDITS_DONE'
