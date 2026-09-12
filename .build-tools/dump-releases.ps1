$ErrorActionPreference = 'Stop'
# Dumps every GitHub release body to .build-tools/release-bodies/<tag>.md so the text can be
# inspected. Correctness is asserted byte-exactly by verify-releases.ps1 (this script only
# fetches, which keeps it free of non-ASCII literals).
$inFile = Join-Path $env:TEMP 'ghcred-in.txt'
$outFile = Join-Path $env:TEMP 'ghcred-out.txt'
$errFile = Join-Path $env:TEMP 'ghcred-err.txt'
[System.IO.File]::WriteAllText($inFile, "protocol=https`nhost=github.com`n`n", [System.Text.Encoding]::ASCII)
Start-Process -FilePath 'git' -ArgumentList 'credential', 'fill' -RedirectStandardInput $inFile -RedirectStandardOutput $outFile -RedirectStandardError $errFile -NoNewWindow -Wait
$cred = [System.IO.File]::ReadAllText($outFile)
Remove-Item $inFile, $outFile, $errFile -Force -ErrorAction SilentlyContinue
$line = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
$headers = @{ 'Authorization' = "Bearer $($line.Substring(9).Trim())"; 'User-Agent' = 'cp-dump' }
$repo = 'CNTianCheng/coreprotect-fabric'

$rels = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases?per_page=40" -Headers $headers
$dumpDir = Join-Path $PSScriptRoot 'release-bodies'
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)

foreach ($r in $rels) {
    $path = Join-Path $dumpDir ($r.tag_name + '.md')
    [System.IO.File]::WriteAllText($path, [string]$r.body, $utf8)
    $assets = ($r.assets | ForEach-Object { $_.name }) -join ','
    Write-Output ("{0,-22} len={1,-6} asset={2}" -f $r.tag_name, ([string]$r.body).Length, $assets)
}
Write-Output "bodies dumped to $dumpDir"
