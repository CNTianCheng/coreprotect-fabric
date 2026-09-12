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
$superseded = Read-Utf8 'superseded.txt'

$supersededBy = @{
    'v1.8.1'           = 'v1.8.2'
    'v1.8.1-mc1.21.11' = 'v1.8.2-mc1.21.11'
    'v1.8.1-mc26.1.2'  = 'v1.8.2-mc26.1.2'
    'v1.0.0-mc1.21.11' = 'v1.8.2-mc1.21.11'
    'v1.0.0-mc26.1.2'  = 'v1.8.2-mc26.1.2'
}

$inFile = Join-Path $env:TEMP 'ghcred-in.txt'
$outFile = Join-Path $env:TEMP 'ghcred-out.txt'
$errFile = Join-Path $env:TEMP 'ghcred-err.txt'
[System.IO.File]::WriteAllText($inFile, "protocol=https`nhost=github.com`n`n", [System.Text.Encoding]::ASCII)
Start-Process -FilePath 'git' -ArgumentList 'credential', 'fill' -RedirectStandardInput $inFile -RedirectStandardOutput $outFile -RedirectStandardError $errFile -NoNewWindow -Wait
$cred = [System.IO.File]::ReadAllText($outFile)
Remove-Item $inFile, $outFile, $errFile -Force -ErrorAction SilentlyContinue
$tokenLine = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
$headers = @{ 'Authorization' = "Bearer $($tokenLine.Substring(9).Trim())"; 'User-Agent' = 'cp-verify' }
$repo = 'CNTianCheng/coreprotect-fabric'

$rels = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases?per_page=40" -Headers $headers
$problems = 0
foreach ($r in $rels) {
    $tag = $r.tag_name
    $body = [string]$r.body
    if (-not $body) { continue }
    $mc = $null
    if ($tag -match '-mc([0-9][0-9.]*)$') { $mc = $Matches[1] } elseif ($tag -match '^v1\.[0-9]+\.[0-9]+$') { $mc = '1.21' }
    if (-not $mc) { continue }
    $java = '21'; $mz = ''; $me = ''
    if ($mc.StartsWith('26.')) { $java = '25'; $mz = $noteZh; $me = $noteEn }
    $zh = $installZh.Replace('@MC@', $mc).Replace('@JAVA@', $java).Replace('@NOTE@', $mz)
    $en = $installEn.Replace('@MC@', $mc).Replace('@JAVA@', $java).Replace('@NOTE@', $me)
    $missing = @()
    if (-not $body.Contains($zh)) { $missing += 'zh-install-line' }
    if (-not $body.Contains($en)) { $missing += 'en-install-line' }
    if ($supersededBy.ContainsKey($tag)) {
        $note = $superseded.Replace('@TAG@', $supersededBy[$tag])
        if (-not $body.Contains($note)) { $missing += 'superseded-note' }
    }
    if ($missing.Count -gt 0) {
        Write-Output ("{0}: MISSING {1}" -f $tag, ($missing -join ', '))
        $problems++
    } else {
        Write-Output ("{0}: ok" -f $tag)
    }
}
if ($problems -eq 0) { Write-Output 'ALL RELEASE BODIES VERIFIED' } else { Write-Output ("problem releases: " + $problems) }
