param(
    [int]$Port = 25575,
    [string]$Password = 'testpass',
    [string]$ServerHost = '127.0.0.1'
)
$ErrorActionPreference = 'Stop'

# wait for server to come up
$client = $null
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        $client = [System.Net.Sockets.TcpClient]::new($ServerHost, $Port)
        break
    } catch {
        Start-Sleep -Seconds 3
    }
}
if ($null -eq $client) { Write-Host 'RCON connect failed'; exit 1 }
$stream = $client.GetStream()
$stream.ReadTimeout = 10000

function Send-Packet([int]$id, [int]$type, [string]$payload) {
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($payload + "`0")
    $len = 4 + 4 + 4 + $bytes.Length + 1
    $ms = [System.IO.MemoryStream]::new()
    $bw = [System.IO.BinaryWriter]::new($ms)
    $bw.Write([int]$len)
    $bw.Write([int]$id)
    $bw.Write([int]$type)
    $bw.Write($bytes)
    $bw.Write([byte]0)
    $arr = $ms.ToArray()
    $stream.Write($arr, 0, $arr.Length)
    $stream.Flush()
}

function Read-Packet {
    $lenBytes = New-Object byte[] 4
    $read = 0
    while ($read -lt 4) {
        $n = $stream.Read($lenBytes, $read, 4 - $read)
        if ($n -le 0) { return $null }
        $read += $n
    }
    $len = [BitConverter]::ToInt32($lenBytes, 0)
    if ($len -lt 10 -or $len -gt 65536) { return $null }
    $buf = New-Object byte[] $len
    $read = 0
    while ($read -lt $len) {
        $n = $stream.Read($buf, $read, $len - $read)
        if ($n -le 0) { return $null }
        $read += $n
    }
    $id = [BitConverter]::ToInt32($buf, 0)
    $type = [BitConverter]::ToInt32($buf, 4)
    $payload = [System.Text.Encoding]::ASCII.GetString($buf, 8, $len - 10)
    return @{ id = $id; type = $type; payload = $payload }
}

Send-Packet 1 3 $Password
$resp = Read-Packet
if ($null -eq $resp -or $resp.id -ne 1) { Write-Host 'RCON AUTH FAILED'; $client.Close(); exit 1 }
Write-Host 'RCON authenticated'

$commands = @(
    @{ c = 'co debug natural'; d = 2 },
    @{ c = 'co lookup a:#fire'; d = 50 },
    @{ c = 'co lookup a:#water'; d = 3 },
    @{ c = 'co lookup a:#tnt'; d = 3 },
    @{ c = 'co lookup a:#fire'; d = 55 },
    @{ c = 'co status'; d = 3 },
    @{ c = 'stop'; d = 2 }
)
$i = 2
foreach ($cmd in $commands) {
    Start-Sleep -Seconds $cmd.d
    Send-Packet $i 2 $cmd.c
    $resp = Read-Packet
    if ($null -ne $resp) {
        Write-Host ("=== RCON [{0}] ===" -f $cmd.c)
        Write-Host $resp.payload.Trim()
    }
    $i++
}
$client.Close()
Write-Host 'RCON done'
