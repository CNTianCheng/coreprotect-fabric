$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$repairDir = Join-Path $PSScriptRoot 'repair'

function Read-Utf8([string]$name) {
    return ([System.IO.File]::ReadAllText((Join-Path $repairDir $name), [System.Text.Encoding]::UTF8)).TrimEnd("`r", "`n")
}

$installZh = Read-Utf8 'install-zh.txt'
$installEn = Read-Utf8 'install-en.txt'
$noteZh = Read-Utf8 'note-zh.txt'
$noteEn = Read-Utf8 'note-en.txt'
$superseded = ([System.IO.File]::ReadAllText((Join-Path $repairDir 'superseded.txt'), [System.Text.Encoding]::UTF8)).TrimEnd("`r", "`n")

# releases that must point at their newer build
$supersededBy = @{
    'v1.8.1'             = 'v1.8.2'
    'v1.8.1-mc1.21.11'   = 'v1.8.2-mc1.21.11'
    'v1.8.1-mc26.1.2'    = 'v1.8.2-mc26.1.2'
    'v1.0.0-mc1.21.11'   = 'v1.8.2-mc1.21.11'
    'v1.0.0-mc26.1.2'    = 'v1.8.2-mc26.1.2'
}

$inFile = Join-Path $env:TEMP 'ghcred-in.txt'
$outFile = Join-Path $env:TEMP 'ghcred-out.txt'
$errFile = Join-Path $env:TEMP 'ghcred-err.txt'
[System.IO.File]::WriteAllText($inFile, "protocol=https`nhost=github.com`n`n", [System.Text.Encoding]::ASCII)
Start-Process -FilePath 'git' -ArgumentList 'credential', 'fill' -RedirectStandardInput $inFile -RedirectStandardOutput $outFile -RedirectStandardError $errFile -NoNewWindow -Wait
$cred = [System.IO.File]::ReadAllText($outFile)
Remove-Item $inFile, $outFile, $errFile -Force -ErrorAction SilentlyContinue
$tokenLine = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
$headers = @{ 'Authorization' = "Bearer $($tokenLine.Substring(9).Trim())"; 'User-Agent' = 'cp-repair' }
$repo = 'CNTianCheng/coreprotect-fabric'

$rels = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases?per_page=40" -Headers $headers
foreach ($r in $rels) {
    $tag = $r.tag_name
    $body = [string]$r.body
    if (-not $body) { continue }

    $lines = $body -split "`n"

    # 1) drop any junk before the first markdown heading (the broken "superseded" note)
    $firstHeading = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].StartsWith('# ')) { $firstHeading = $i; break }
    }
    if ($firstHeading -gt 0) { $lines = $lines[$firstHeading..($lines.Count - 1)] }

    # 2) work out the minecraft version / java / mapping note for this release
    $mc = $null
    if ($tag -match '-mc([0-9][0-9.]*)$') { $mc = $Matches[1] }
    elseif ($tag -match '^v1\.[0-9]+\.[0-9]+$') { $mc = '1.21' }
    if (-not $mc) { Write-Output "skip $tag (unknown minecraft version)"; continue }
    $java = '21'
    $mappingZh = ''
    $mappingEn = ''
    if ($mc.StartsWith('26.')) {
        $java = '25'
        $mappingZh = $noteZh
        $mappingEn = $noteEn
    }

    # 3) rebuild the install lines from UTF-8 templates (fixes the garbled mapping note)
    $zhLine = $installZh.Replace('@MC@', $mc).Replace('@JAVA@', $java).Replace('@NOTE@', $mappingZh)
    $enLine = $installEn.Replace('@MC@', $mc).Replace('@JAVA@', $java).Replace('@NOTE@', $mappingEn)
    $changed = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        # identify the lines by ASCII markers only: a .ps1 is read as ANSI by Windows
        # PowerShell, so Chinese literals in this file would never match
        if ($lines[$i] -like '- Requirements: Minecraft*') {
            if ($lines[$i] -ne $enLine) { $lines[$i] = $enLine; $changed = $true }
        } elseif ($lines[$i] -like '- *Java ***Fabric Loader*') {
            if ($lines[$i] -ne $zhLine) { $lines[$i] = $zhLine; $changed = $true }
        }
    }

    $newBody = ($lines -join "`n").TrimEnd()

    # 4) prepend the (correct) superseded note where it belongs
    if ($supersededBy.ContainsKey($tag)) {
        $note = $superseded.Replace('@TAG@', $supersededBy[$tag])
        if (-not $newBody.Contains($note)) {
            $newBody = $note + "`n`n" + $newBody
            $changed = $true
        }
    }

    if ($newBody -eq $body) { Write-Output "unchanged $tag"; continue }

    $patch = @{ body = $newBody } | ConvertTo-Json -Depth 4
    Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$($r.id)" -Headers $headers -Method Patch -Body ([System.Text.Encoding]::UTF8.GetBytes($patch)) -ContentType 'application/json; charset=utf-8' | Out-Null
    Write-Output "repaired $tag"
}
Write-Output 'repair done'
