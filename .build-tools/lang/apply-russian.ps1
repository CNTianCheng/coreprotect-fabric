<#
    Writes assets/coreprotect/lang/ru_ru.json into every build directory, using that
    directory's own en_us.json as the source of truth for the key set and key order.

    The Russian strings live in ru_ru.json next to this script, as a UTF-8 data file:
    Cyrillic must never be written inside a .ps1, because Windows PowerShell 5.1 reads
    a BOM-less script as ANSI and would corrupt every string it sends.

    Checks every key for placeholder parity with en_us ({0}-style and %s-style tokens),
    so a translation cannot silently drop an argument. A key that the template does not
    cover falls back to the English text and is reported.

    Usage:
        powershell -File apply-russian.ps1 [-Root <repo root>] [-Check]
#>
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    # The maintained full-feature builds. Their en_us.json files are identical (114 keys),
    # so ru_ru.json comes out byte-identical for all three. The legacy build directories
    # ship an older, smaller key set (85 keys, different placeholder counts in
    # status.header / status.counts / status.logging), so each of those would need its own
    # translations rather than this file.
    [string[]]$Projects = @('coreprotect-fabric-1.21', 'coreprotect-fabric-1.21.11', 'coreprotect-fabric-26.1.2'),
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
$plainUtf8 = New-Object System.Text.UTF8Encoding($false)

function Read-Json([string]$path) {
    return ($strictUtf8.GetString([System.IO.File]::ReadAllBytes($path)) | ConvertFrom-Json)
}

function Escape-Json([string]$value) {
    $out = $value -replace '\\', '\\'
    $out = $out -replace '"', '\"'
    $out = $out -replace "`r", ''
    $out = $out -replace "`n", '\n'
    $out = $out -replace "`t", '\t'
    return $out
}

function Get-Placeholders([string]$value) {
    $braces = @([regex]::Matches($value, '\{\d+\}') | ForEach-Object { $_.Value })
    $percents = @([regex]::Matches($value, '%[sd]') | ForEach-Object { $_.Value })
    return ((@($braces) + @($percents) | Sort-Object) -join ',')
}

$template = Read-Json (Join-Path $PSScriptRoot 'ru_ru.json')
$ruKeys = @($template.PSObject.Properties.Name)
Write-Host ("template : ru_ru.json holds {0} key(s)" -f $ruKeys.Count)

$problems = 0
$changed = 0

$targets = @()
foreach ($project in $Projects) {
    $enPath = Join-Path $Root "$project\src\main\resources\assets\coreprotect\lang\en_us.json"
    if (Test-Path -LiteralPath $enPath) {
        $targets += Get-Item -LiteralPath $enPath
    } else {
        Write-Host ("  ! no en_us.json under {0}" -f $project)
        $problems++
    }
}
Write-Host ("targets  : {0} of {1} build(s)" -f $targets.Count, $Projects.Count)
foreach ($enPath in $targets) {
    $langDir = $enPath.DirectoryName
    $en = Read-Json $enPath.FullName
    $enKeys = @($en.PSObject.Properties.Name)
    $missing = @($enKeys | Where-Object { $ruKeys -notcontains $_ })
    $unused = @($ruKeys | Where-Object { $enKeys -notcontains $_ })

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('{')
    for ($i = 0; $i -lt $enKeys.Count; $i++) {
        $key = $enKeys[$i]
        $value = [string]$template.$key
        if (-not $value) { $value = [string]$en.$key }
        $comma = if ($i -lt $enKeys.Count - 1) { ',' } else { '' }
        $lines.Add(('  "{0}": "{1}"{2}' -f (Escape-Json $key), (Escape-Json $value), $comma))

        $ruPlaceholders = Get-Placeholders $value
        $enPlaceholders = Get-Placeholders ([string]$en.$key)
        if ($ruPlaceholders -ne $enPlaceholders) {
            Write-Host ("  ! placeholder mismatch in {0}: en [{1}] ru [{2}]" -f $key, $enPlaceholders, $ruPlaceholders)
            $problems++
        }
    }
    $lines.Add('}')
    $text = ($lines -join "`n") + "`n"

    $outPath = Join-Path $langDir 'ru_ru.json'
    $relative = $outPath.Substring($Root.Length).TrimStart('\')
    $same = $false
    if (Test-Path -LiteralPath $outPath) {
        $same = ($strictUtf8.GetString([System.IO.File]::ReadAllBytes($outPath)) -eq $text)
    }
    if (-not $same) {
        if (-not $Check) { [System.IO.File]::WriteAllText($outPath, $text, $plainUtf8) }
        $changed++
    }

    $state = if ($same) { 'up to date' } elseif ($Check) { 'would write' } else { 'written' }
    $notes = ''
    if ($missing.Count -gt 0) { $notes += ", $($missing.Count) untranslated: $($missing -join ', ')" }
    if ($unused.Count -gt 0) { $notes += ", $($unused.Count) template key(s) not used by this build" }
    Write-Host ("  {0,-11} {1} ({2} keys{3})" -f $state, $relative, $enKeys.Count, $notes)
    if ($missing.Count -gt 0) { $problems++ }
}

$verb = if ($Check) { 'to update' } else { 'updated' }
Write-Host ("summary  : {0} file(s) {1}, {2} problem(s)" -f $changed, $verb, $problems)
if ($problems -gt 0) { exit 1 }
