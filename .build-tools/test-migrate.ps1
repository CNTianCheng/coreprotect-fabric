param(
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [Parameter(Mandatory = $true)][string]$Gradle,
    [string]$Jdk = 'jdk-21.0.12.1+1',
    [Parameter(Mandatory = $true)][string]$SeedDb,
    [string]$Tag = 'migrate'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jdk = Join-Path $root ".build-tools\jdk\$Jdk"
if (-not (Test-Path $jdk)) { $jdk = 'C:\Program Files\Java\graalvm-jdk-25.0.2+10.1' }
$env:JAVA_HOME = $jdk
Set-Location $WorkDir
$stale = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$WorkDir*" }
if ($stale) { $stale | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; Start-Sleep -Seconds 6 }

# seed the run directory with a database that still uses the old schema
$runDir = Join-Path $WorkDir 'run'
Get-ChildItem $runDir -Filter 'coreprotect.db*' -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
Copy-Item $SeedDb (Join-Path $runDir 'coreprotect.db')
$before = (Get-Item (Join-Path $runDir 'coreprotect.db')).Length
Write-Output ("seeded database: {0:N0} bytes ({1:N2} MB)" -f $before, ($before / 1MB))

$out = Join-Path $WorkDir "$Tag-log.txt"
Remove-Item $out -Force -ErrorAction SilentlyContinue
$p = Start-Process -FilePath $Gradle -ArgumentList @('runServer', '--no-daemon', '--console=plain') -WorkingDirectory $WorkDir -RedirectStandardOutput $out -RedirectStandardError "$Tag-err.txt" -PassThru -WindowStyle Hidden
Write-Output "starting (pid $($p.Id))"

$client = $null
for ($a = 0; $a -lt 200; $a++) {
    try { $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', 25575); break } catch { Start-Sleep -Seconds 3 }
}
if ($null -eq $client) { Write-Output 'RCON CONNECT FAILED'; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; exit 1 }
$stream = $client.GetStream(); $stream.ReadTimeout = 60000
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
    $len = [BitConverter]::ToInt32($lb, 0); if ($len -lt 10 -or $len -gt 262144) { return $null }
    $buf = New-Object byte[] $len; $rd = 0
    while ($rd -lt $len) { $n = $stream.Read($buf, $rd, $len - $rd); if ($n -le 0) { return $null }; $rd += $n }
    return @{ id = [BitConverter]::ToInt32($buf, 0); payload = [System.Text.Encoding]::ASCII.GetString($buf, 8, $len - 10) }
}
Send-P 1 3 'testpass'; $r = Read-P
if ($null -eq $r -or $r.id -ne 1) { Write-Output 'RCON AUTH FAILED'; exit 1 }
Write-Output 'RCON authenticated'

Start-Sleep -Seconds 30   # let the migration + compaction finish
$mid = (Get-Item (Join-Path $runDir 'coreprotect.db')).Length
Write-Output ('database after migration: {0:N0} bytes ({1:N2} MB)' -f $mid, ($mid / 1MB))
Write-Output ('migration saved: {0:N1}%' -f ((1 - $mid / $before) * 100))
$cmds = @(
    @{ c = 'co status'; d = 3 },
    @{ c = 'co lookup u:Steve t:30d p:1'; d = 4 },
    @{ c = 'co lookup r:120 t:30d p:1'; d = 4 },
    @{ c = 'co lookup a:#tnt t:30d p:1'; d = 4 },
    @{ c = 'co lookup b:stone t:30d p:1'; d = 4 },
    @{ c = 'co lookup u:Alex t:1d p:1'; d = 4 },
    @{ c = 'co lookup t:1h p:1'; d = 4 },
    @{ c = 'co lookup e:Steve t:1d p:1'; d = 4 },
    @{ c = 'co rollback t:1h r:40'; d = 20 },
    @{ c = 'co status'; d = 3 },
    @{ c = 'stop'; d = 4 }
)
$i = 2
foreach ($cmd in $cmds) {
    Start-Sleep -Seconds $cmd.d
    Send-P $i 2 $cmd.c
    Start-Sleep -Milliseconds 600
    $r = Read-P
    $text = if ($null -ne $r) { $r.payload.Trim() } else { '(none)' }
    $lines = $text -split "`r?`n"
    Write-Output ("=== [{0}] ===" -f $cmd.c)
    if ($lines.Count -gt 4) {
        Write-Output ("    {0}" -f $lines[0])
        $lines[1..([Math]::Min(3, $lines.Count - 1))] | ForEach-Object { Write-Output ("    {0}" -f $_) }
        Write-Output ("    ... ({0} lines total)" -f $lines.Count)
    } else {
        $lines | ForEach-Object { Write-Output ("    {0}" -f $_) }
    }
    $i++
}
$client.Close()
Wait-Process -Id $p.Id -Timeout 120 -ErrorAction SilentlyContinue
if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

$after = (Get-Item (Join-Path $runDir 'coreprotect.db')).Length
Write-Output ("database after migration: {0:N0} bytes ({1:N2} MB)" -f $after, ($after / 1MB))
Write-Output ("saved: {0:N1}%   ratio {1:N3}" -f ((1 - $after / $before) * 100), ($after / $before))
Write-Output '=== migration log lines ==='
Select-String -Path $out -Pattern 'schema migrated|Compressing the database|compacted|migrat' -ErrorAction SilentlyContinue | ForEach-Object { $_.Line }
Write-Output '=== errors in log ==='
$errs = Select-String -Path $out -Pattern 'ERROR|Exception|failed' -ErrorAction SilentlyContinue | Where-Object { $_.Line -notmatch 'SERVER IS RUNNING|offline/insecure|authenticate usernames|hackers to connect|online-mode' }
if ($errs) { $errs | Select-Object -First 10 | ForEach-Object { $_.Line } } else { Write-Output 'none' }
Write-Output 'MIGRATE_TEST_DONE'
