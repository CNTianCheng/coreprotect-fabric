$ErrorActionPreference = 'Stop'
$inFile = Join-Path $env:TEMP 'ghcred-in.txt'
$outFile = Join-Path $env:TEMP 'ghcred-out.txt'
$errFile = Join-Path $env:TEMP 'ghcred-err.txt'
[System.IO.File]::WriteAllText($inFile, "protocol=https`nhost=github.com`n`n", [System.Text.Encoding]::ASCII)
Start-Process -FilePath 'git' -ArgumentList 'credential', 'fill' -RedirectStandardInput $inFile -RedirectStandardOutput $outFile -RedirectStandardError $errFile -NoNewWindow -Wait
$cred = [System.IO.File]::ReadAllText($outFile)
Remove-Item $inFile, $outFile, $errFile -Force -ErrorAction SilentlyContinue
$line = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
$headers = @{ 'Authorization' = "Bearer $($line.Substring(9).Trim())"; 'User-Agent' = 'cp-info' }
$repo = 'CNTianCheng/coreprotect-fabric'

$r = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo" -Headers $headers
Write-Output ("name        : " + $r.full_name)
Write-Output ("description : " + $r.description)
Write-Output ("homepage    : " + $r.homepage)
Write-Output ("topics      : " + ($r.topics -join ', '))
Write-Output ("license     : " + $r.license.spdx_id)
Write-Output ("default br  : " + $r.default_branch)
Write-Output ("has issues  : " + $r.has_issues + " / wiki: " + $r.has_wiki + " / discussions: " + $r.has_discussions)
Write-Output ""
$p = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/community/profile" -Headers $headers
Write-Output ("health_percentage: " + $p.health_percentage)
Write-Output ("code_of_conduct  : " + $(if ($p.files.code_of_conduct) { $p.files.code_of_conduct.url } else { 'MISSING' }))
Write-Output ("contributing     : " + $(if ($p.files.contributing) { $p.files.contributing.url } else { 'MISSING' }))
Write-Output ("issue_template   : " + $(if ($p.files.issue_template) { $p.files.issue_template.url } else { 'MISSING' }))
Write-Output ("pull_request_tpl : " + $(if ($p.files.pull_request_template) { $p.files.pull_request_template.url } else { 'MISSING' }))
Write-Output ("license          : " + $(if ($p.files.license) { $p.files.license.url } else { 'MISSING' }))
Write-Output ("readme           : " + $(if ($p.files.readme) { $p.files.readme.url } else { 'MISSING' }))
