$ErrorActionPreference = 'Stop'
# Verifies that the files pushed to GitHub are byte-identical to the local working copy
# (catches encoding damage on the way out). ASCII-only: hashes, no text literals.
$inFile = Join-Path $env:TEMP 'ghcred-in.txt'
$outFile = Join-Path $env:TEMP 'ghcred-out.txt'
$errFile = Join-Path $env:TEMP 'ghcred-err.txt'
[System.IO.File]::WriteAllText($inFile, "protocol=https`nhost=github.com`n`n", [System.Text.Encoding]::ASCII)
Start-Process -FilePath 'git' -ArgumentList 'credential', 'fill' -RedirectStandardInput $inFile -RedirectStandardOutput $outFile -RedirectStandardError $errFile -NoNewWindow -Wait
$cred = [System.IO.File]::ReadAllText($outFile)
Remove-Item $inFile, $outFile, $errFile -Force -ErrorAction SilentlyContinue
$line = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
$headers = @{ 'Authorization' = "Bearer $($line.Substring(9).Trim())"; 'User-Agent' = 'cp-hash' }
$repo = 'CNTianCheng/coreprotect-fabric'
$root = Split-Path $PSScriptRoot -Parent

$files = @(
    'README.md', 'OVERVIEW.md', 'OVERVIEW_EN.md', 'CHANGELOG.md', 'CONTRIBUTING.md',
    'SECURITY.md', 'CODE_OF_CONDUCT.md', 'LICENSE',
    '.github/PULL_REQUEST_TEMPLATE.md',
    '.github/ISSUE_TEMPLATE/bug_report.yml',
    '.github/ISSUE_TEMPLATE/feature_request.yml',
    '.github/ISSUE_TEMPLATE/config.yml',
    '.github/workflows/build.yml',
    '.github/dependabot.yml'
)

$bad = 0
foreach ($f in $files) {
    $local = Join-Path $root ($f.Replace('/', '\'))
    if (-not (Test-Path $local)) { Write-Output ("{0,-48} MISSING LOCALLY" -f $f); $bad++; continue }
    $json = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/contents/$f" -Headers $headers
    $remote = [System.Convert]::FromBase64String(($json.content -replace "`n", ''))
    $localBytes = [System.IO.File]::ReadAllBytes($local)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $h1 = [BitConverter]::ToString($sha.ComputeHash($localBytes)).Replace('-', '')
    $h2 = [BitConverter]::ToString($sha.ComputeHash($remote)).Replace('-', '')
    if ($h1 -eq $h2) {
        Write-Output ("{0,-48} ok" -f $f)
    } else {
        Write-Output ("{0,-48} DIFFERS (local {1} / remote {2})" -f $f, $h1.Substring(0, 12), $h2.Substring(0, 12))
        if ($remote.Length -ne $localBytes.Length) { Write-Output ("    length local={0} remote={1}" -f $localBytes.Length, $remote.Length) }
        $bad++
    }
}
if ($bad -eq 0) { Write-Output 'ALL PUSHED FILES MATCH THE LOCAL COPY' } else { Write-Output ("mismatches: " + $bad) }
