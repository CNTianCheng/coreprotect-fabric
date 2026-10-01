# Starts a throwaway server and checks that the Russian language file is picked up.
# Responses are written as UTF-8 to russian-test-output.txt because the PowerShell 5.1
# console cannot render Cyrillic; the console only prints ASCII statistics.
param(
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [Parameter(Mandatory = $true)][string]$Gradle,
    [string]$Jdk = 'jdk-21.0.12.1+1'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $root ".build-tools\jdk\$Jdk"
if (-not (Test-Path $jdk)) { $jdk = 'C:\Program Files\Java\graalvm-jdk-25.0.2+10.1' }
$env:JAVA_HOME = $jdk
Set-Location $WorkDir

$stale = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$WorkDir*" }
if ($stale) { $stale | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; Start-Sleep -Seconds 5 }

$runDir = Join-Path $WorkDir 'run'
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
Set-Content -Path (Join-Path $runDir 'eula.txt') -Value 'eula=true' -Force
Set-Content -Path (Join-Path $runDir 'server.properties') -Value @(
    'online-mode=false','server-port=25577','level-type=minecraft:flat','enable-rcon=true','rcon.port=25575','rcon.password=testpass','spawn-protection=0'
) -Force

$out = Join-Path $WorkDir 'russian-log.txt'
Remove-Item $out -Force -ErrorAction SilentlyContinue
$p = Start-Process -FilePath $Gradle -ArgumentList @('runServer','--no-daemon','--console=plain') -WorkingDirectory $WorkDir -RedirectStandardOutput $out -RedirectStandardError 'russian-err.txt' -PassThru -WindowStyle Hidden
Write-Host "starting (pid $($p.Id))"
$client = $null
for ($a = 0; $a -lt 150; $a++) {
    try { $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', 25575); break } catch { Start-Sleep -Seconds 3 }
}
if ($null -eq $client) { Write-Host 'RCON CONNECT FAILED'; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; exit 1 }
$stream = $client.GetStream(); $stream.ReadTimeout = 20000
function Send-P([int]$id, [int]$type, [string]$payload) {
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($payload + "`0")
    $len = 4 + 4 + $bytes.Length + 1
    $ms = [System.IO.MemoryStream]::new(); $bw = [System.IO.BinaryWriter]::new($ms)
    $bw.Write([int]$len); $bw.Write([int]$id); $bw.Write([int]$type); $bw.Write($bytes); $bw.Write([byte]0)
    $arr = $ms.ToArray(); $stream.Write($arr, 0, $arr.Length); $stream.Flush()
}
function Read-P {
    $lb = New-Object byte[] 4; $rd = 0
    while ($rd -lt 4) { $n = $stream.Read($lb, $rd, 4 - $rd); if ($n -le 0) { return $null }; $rd += $n }
    $len = [BitConverter]::ToInt32($lb, 0); if ($len -lt 10 -or $len -gt 65536) { return $null }
    $buf = New-Object byte[] $len; $rd = 0
    while ($rd -lt $len) { $n = $stream.Read($buf, $rd, $len - $rd); if ($n -le 0) { return $null }; $rd += $n }
    # Minecraft answers in UTF-8, not ASCII
    return [System.Text.Encoding]::UTF8.GetString($buf, 8, $len - 10)
}
Send-P 1 3 'testpass'
$auth = Read-P
if ($null -eq $auth) { Write-Host 'RCON AUTH FAILED'; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; exit 1 }
Write-Host 'RCON authenticated'

$lines = New-Object System.Collections.Generic.List[string]
$cmds = @(
    @{ c = 'co language list'; d = 3 },
    @{ c = 'co help'; d = 3 },
    @{ c = 'co language ru_ru'; d = 3 },
    @{ c = 'co help'; d = 3 },
    @{ c = 'co status'; d = 3 },
    @{ c = 'co language en_us'; d = 3 },
    @{ c = 'co help'; d = 3 },
    @{ c = 'stop'; d = 3 }
)
$i = 2
foreach ($cmd in $cmds) {
    Start-Sleep -Seconds $cmd.d
    Send-P $i 2 $cmd.c
    Start-Sleep -Milliseconds 500
    $payload = Read-P
    if ($null -eq $payload) { $payload = '(no response)' }
    $lines.Add("=== [{0}] ===" -f $cmd.c)
    $lines.Add($payload.Trim())
    $cyr = ([regex]::Matches($payload, '[\u0400-\u04FF]')).Count
    $latin = ([regex]::Matches($payload, '[A-Za-z]')).Count
    Write-Host ("[ {0,-20} ] cyrillic={1,-6} latin={2,-6} chars={3}" -f $cmd.c, $cyr, $latin, $payload.Length)
    $i++
}
$client.Close()
Wait-Process -Id $p.Id -Timeout 90 -ErrorAction SilentlyContinue
if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }

[System.IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'russian-test-output.txt'), $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host '=== errors in log ==='
Select-String -Path $out -Pattern 'ERROR|Exception|Failed to load|mixin' -ErrorAction SilentlyContinue |
    Select-Object -First 10 | ForEach-Object { $_.Line }
Write-Host '=== language lines in log ==='
Select-String -Path $out -Pattern 'language|lang' -ErrorAction SilentlyContinue |
    Select-Object -First 10 | ForEach-Object { $_.Line }
Write-Host 'RUSSIAN_TEST_DONE'
