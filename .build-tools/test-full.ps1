param(
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [Parameter(Mandatory = $true)][string]$Gradle,
    [string]$Jdk = 'jdk-21.0.12.1+1',
    [switch]$Fresh
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
if ($Fresh) {
    Remove-Item (Join-Path $runDir 'config') -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $runDir 'coreprotect.db*') -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $runDir 'coreprotect-fabric.crash-marker') -Force -ErrorAction SilentlyContinue
}

$out = Join-Path $WorkDir 'full-log.txt'
Remove-Item $out -Force -ErrorAction SilentlyContinue
$p = Start-Process -FilePath $Gradle -ArgumentList @('runServer','--no-daemon','--console=plain') -WorkingDirectory $WorkDir -RedirectStandardOutput $out -RedirectStandardError 'full-err.txt' -PassThru -WindowStyle Hidden
Write-Host "starting (pid $($p.Id))"
$client = $null
for ($a = 0; $a -lt 150; $a++) {
    try { $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', 25575); break } catch { Start-Sleep -Seconds 3 }
}
if ($null -eq $client) { Write-Host 'RCON CONNECT FAILED'; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; exit 1 }
$stream = $client.GetStream(); $stream.ReadTimeout = 25000
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
Send-P 1 3 'testpass'
$r = Read-P
if ($null -eq $r -or $r.id -ne 1) { Write-Host 'RCON AUTH FAILED'; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; exit 1 }
Write-Host 'RCON authenticated'

$cmds = @(
    @{ c = 'weather clear 1000000'; d = 2 },
    @{ c = 'co status'; d = 2 },
    @{ c = 'co debug natural'; d = 40 },
    @{ c = 'co lookup a:#tnt'; d = 6 },
    @{ c = 'co lookup a:#water'; d = 6 },
    @{ c = 'co lookup a:#fire'; d = 6 },
    @{ c = 'co lookup a:#piston'; d = 6 },
    @{ c = 'co lookup u:#hopper a:#container'; d = 6 },
    @{ c = 'co lookup a:container'; d = 6 },
    @{ c = 'co lookup b:white_wool'; d = 6 },
    @{ c = 'co lookup r:60 t:10m p:1'; d = 6 },
    @{ c = 'co lookup a:#tnt p:2'; d = 6 },
    @{ c = 'co lookup e:#tnt'; d = 6 },
    @{ c = 'co lookup a:invalidaction'; d = 4 },
    @{ c = 'co lookup t:banana'; d = 4 },
    @{ c = 'co online'; d = 4 },
    @{ c = 'co language list'; d = 4 },
    @{ c = 'co help'; d = 4 },
    @{ c = 'co reload'; d = 4 },
    # ---- rollback / undo ----
    @{ c = 'execute if block 7 -62 6 minecraft:dirt'; d = 2 },
    @{ c = 'co rollback t:5m r:80'; d = 12 },
    @{ c = 'execute if block 7 -62 6 minecraft:dirt'; d = 2 },
    @{ c = 'co undo'; d = 12 },
    @{ c = 'execute if block 7 -62 6 minecraft:air'; d = 2 },
    # ---- restore ----
    @{ c = 'co restore t:5m r:80'; d = 12 },
    @{ c = 'co undo'; d = 12 },
    @{ c = 'co lookup a:#tnt'; d = 6 },
    # ---- purge ----
    @{ c = 'co status'; d = 3 },
    @{ c = 'co purge t:1s'; d = 8 },
    @{ c = 'co status'; d = 3 },
    @{ c = 'co lookup a:#tnt'; d = 4 },
    @{ c = 'co purge t:banana'; d = 4 },
    @{ c = 'stop'; d = 3 }
)
$i = 2
foreach ($cmd in $cmds) {
    Start-Sleep -Seconds $cmd.d
    Send-P $i 2 $cmd.c
    Start-Sleep -Milliseconds 500
    $r = Read-P
    Write-Host ("=== [{0}] ===" -f $cmd.c)
    if ($null -ne $r) { Write-Host $r.payload.Trim() } else { Write-Host '(no response)' }
    $i++
}
$client.Close()
Wait-Process -Id $p.Id -Timeout 120 -ErrorAction SilentlyContinue
if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; Write-Host 'server force-stopped' }

Write-Host '=== log checks ==='
$log = Get-Content $out -Raw -ErrorAction SilentlyContinue
if ($log -match 'Mixin apply for mod coreprotect failed') { Write-Host 'MIXIN_FAILURES: yes' } else { Write-Host 'MIXIN_FAILURES: no' }
if ($log -match 'CoreProtect\] Enabled') { Write-Host 'CORE_ENABLED: yes' } else { Write-Host 'CORE_ENABLED: no' }
Write-Host '=== errors/warnings in log ==='
$log -split "`n" | Select-String -Pattern 'ERROR|WARN|Exception' | Select-Object -First 12 | ForEach-Object { Write-Host $_.Line }
Write-Host 'FULL_TEST_DONE'
