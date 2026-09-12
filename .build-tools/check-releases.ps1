$ErrorActionPreference = 'Stop'
$inFile = Join-Path $env:TEMP 'ghcred-in.txt'
$outFile = Join-Path $env:TEMP 'ghcred-out.txt'
$errFile = Join-Path $env:TEMP 'ghcred-err.txt'
[System.IO.File]::WriteAllText($inFile, "protocol=https`nhost=github.com`n`n", [System.Text.Encoding]::ASCII)
Start-Process -FilePath 'git' -ArgumentList 'credential', 'fill' -RedirectStandardInput $inFile -RedirectStandardOutput $outFile -RedirectStandardError $errFile -NoNewWindow -Wait
$cred = [System.IO.File]::ReadAllText($outFile)
Remove-Item $inFile, $outFile, $errFile -Force -ErrorAction SilentlyContinue
$line = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
$token = $line.Substring(9).Trim()
$h = @{ 'Authorization' = "Bearer $token"; 'User-Agent' = 'cp-check' }
$rels = Invoke-RestMethod -Uri 'https://api.github.com/repos/CNTianCheng/coreprotect-fabric/releases?per_page=40' -Headers $h
Write-Output ("total releases: " + $rels.Count)
foreach ($r in ($rels | Sort-Object created_at -Descending | Select-Object -First 7)) {
    $assets = ($r.assets | ForEach-Object { "{0} ({1} MB)" -f $_.name, [math]::Round($_.size / 1MB, 1) }) -join ', '
    Write-Output ("  {0}  ->  {1}" -f $r.tag_name, $assets)
}
