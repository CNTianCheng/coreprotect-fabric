$ErrorActionPreference = 'Stop'
# Read the credential straight from a pipe: the previous shape parked the PAT in
# %TEMP%\ghcred-out.txt and only cleaned it up best-effort.
$cred = "protocol=https`nhost=github.com`n" | git credential fill 2>$null
$line = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
$token = $line.Substring(9).Trim()
$h = @{ 'Authorization' = "Bearer $token"; 'User-Agent' = 'cp-check' }
$rels = Invoke-RestMethod -Uri 'https://api.github.com/repos/CNTianCheng/coreprotect-fabric/releases?per_page=40' -Headers $h
Write-Output ("total releases: " + $rels.Count)
foreach ($r in ($rels | Sort-Object created_at -Descending | Select-Object -First 7)) {
    $assets = ($r.assets | ForEach-Object { "{0} ({1} MB)" -f $_.name, [math]::Round($_.size / 1MB, 1) }) -join ', '
    Write-Output ("  {0}  ->  {1}" -f $r.tag_name, $assets)
}
