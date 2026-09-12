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
$out = Join-Path $WorkDir 'probe-log.txt'
Remove-Item $out -Force -ErrorAction SilentlyContinue
$p = Start-Process -FilePath $Gradle -ArgumentList @('runServer','--no-daemon','--console=plain') -WorkingDirectory $WorkDir -RedirectStandardOutput $out -RedirectStandardError 'probe-err.txt' -PassThru -WindowStyle Hidden
Write-Host "starting (pid $($p.Id))"
$client = $null
for ($a = 0; $a -lt 150; $a++) { try { $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', 25575); break } catch { Start-Sleep -Seconds 3 } }
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
    return @{ id = [BitConverter]::ToInt32($buf, 0); payload = [System.Text.Encoding]::ASCII.GetString($buf, 8, $len - 10) }
}
Send-P 1 3 'testpass'; $r = Read-P
if ($null -eq $r -or $r.id -ne 1) { Write-Host 'RCON AUTH FAILED'; exit 1 }
Write-Host 'RCON authenticated'
$cmds = @(
    @{ c = 'co debug natural'; d = 12 },
    @{ c = 'execute if block 0 -61 20 minecraft:grass_block'; d = 1 },
    @{ c = 'execute if block 0 -60 20 minecraft:piston'; d = 1 },
    @{ c = 'execute if block 0 -59 20 minecraft:piston'; d = 1 },
    @{ c = 'execute if block 0 -59 19 minecraft:white_wool'; d = 1 },
    @{ c = 'execute if block 0 -59 21 minecraft:redstone_block'; d = 1 },
    @{ c = 'execute if block 0 -60 20 minecraft:piston'; d = 1 },
    @{ c = 'co lookup a:#piston'; d = 3 },
    @{ c = 'execute if block 0 -60 10 minecraft:hopper'; d = 1 },
    @{ c = 'execute if block 0 -61 10 minecraft:chest'; d = 1 },
    @{ c = 'execute if block -8 -61 8 minecraft:white_wool'; d = 1 },
    @{ c = 'execute if block -8 -60 8 minecraft:fire'; d = 1 },
    @{ c = 'co lookup a:#fire'; d = 3 },
    @{ c = 'co status'; d = 2 },
    @{ c = 'stop'; d = 3 }
)
$i = 2
foreach ($cmd in $cmds) {
    Start-Sleep -Seconds $cmd.d
    Send-P $i 2 $cmd.c
    Start-Sleep -Milliseconds 400
    $r = Read-P
    Write-Host ("=== [{0}] => {1}" -f $cmd.c, $(if ($null -ne $r) { $r.payload.Trim() } else { '(none)' }))
    $i++
}
$client.Close()
Wait-Process -Id $p.Id -Timeout 90 -ErrorAction SilentlyContinue
if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
Write-Host 'PROBE_DONE'
