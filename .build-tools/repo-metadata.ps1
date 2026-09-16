$ErrorActionPreference = 'Stop'
$inFile = Join-Path $env:TEMP 'ghcred-in.txt'
$outFile = Join-Path $env:TEMP 'ghcred-out.txt'
$errFile = Join-Path $env:TEMP 'ghcred-err.txt'
[System.IO.File]::WriteAllText($inFile, "protocol=https`nhost=github.com`n`n", [System.Text.Encoding]::ASCII)
Start-Process -FilePath 'git' -ArgumentList 'credential', 'fill' -RedirectStandardInput $inFile -RedirectStandardOutput $outFile -RedirectStandardError $errFile -NoNewWindow -Wait
$cred = [System.IO.File]::ReadAllText($outFile)
Remove-Item $inFile, $outFile, $errFile -Force -ErrorAction SilentlyContinue
$line = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
$headers = @{ 'Authorization' = "Bearer $($line.Substring(9).Trim())"; 'User-Agent' = 'cp-meta' }
$repo = 'CNTianCheng/coreprotect-fabric'

$description = 'CoreProtect-style block/container logging, rollback, restore, lookup and inspection for Minecraft Fabric servers. Independent implementation inspired by the CoreProtect plugin (Bukkit/Spigot). Builds for 1.21, 1.21.11 and 26.1.2.'
$topics = @(
    'minecraft', 'minecraft-mod', 'fabric', 'fabric-mod', 'fabricmc', 'coreprotect',
    'rollback', 'block-logging', 'griefing', 'grief-protection', 'audit-log',
    'sqlite', 'server-management', 'server-tools', 'mod', 'admin-tools',
    'minecraft-server', '1.21', '1.21.11', '26.1.2'
)

$payload = @{
    description = $description
    topics      = $topics
    homepage    = 'https://github.com/CNTianCheng/coreprotect-fabric/releases/latest'
    has_issues  = $true
    has_wiki    = $false
} | ConvertTo-Json -Depth 4

$r = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo" -Headers $headers -Method Patch -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) -ContentType 'application/json; charset=utf-8'
Write-Output ("description : " + $r.description)
Write-Output ("homepage    : " + $r.homepage)
Write-Output ("topics      : " + ($r.topics -join ', '))
Write-Output ("wiki        : " + $r.has_wiki)
