$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
# ask git for the stored github.com credential (works without gh CLI)
$inFile = Join-Path $env:TEMP 'ghcred-in.txt'
$outFile = Join-Path $env:TEMP 'ghcred-out.txt'
$errFile = Join-Path $env:TEMP 'ghcred-err.txt'
[System.IO.File]::WriteAllText($inFile, "protocol=https`nhost=github.com`n`n", [System.Text.Encoding]::ASCII)
Start-Process -FilePath 'git' -ArgumentList 'credential', 'fill' -RedirectStandardInput $inFile -RedirectStandardOutput $outFile -RedirectStandardError $errFile -NoNewWindow -Wait
$cred = [System.IO.File]::ReadAllText($outFile)
Remove-Item $inFile, $outFile, $errFile -Force -ErrorAction SilentlyContinue
$tokenLine = ($cred -split "`n" | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
if (-not $tokenLine) { throw 'no github credential available from git credential fill' }
$headers = @{ 'Authorization' = "Bearer $($tokenLine.Substring(9).Trim())"; 'User-Agent' = 'coreprotect-release' }
$repo = 'CNTianCheng/coreprotect-fabric'

$targets = @(
    @{ ver = '1.21';    tag = 'v1.8.2';            jar = 'coreprotect-fabric-1.21\build\libs\coreprotect-fabric-1.21-1.8.2.jar';         notes = 'release-v182-1.21.md';    prev = 'v1.8.1' },
    @{ ver = '1.21.11'; tag = 'v1.8.2-mc1.21.11';  jar = 'coreprotect-fabric-1.21.11\build\libs\coreprotect-fabric-1.21.11-1.8.2.jar'; notes = 'release-v182-1.21.11.md'; prev = 'v1.8.1-mc1.21.11' },
    @{ ver = '26.1.2';  tag = 'v1.8.2-mc26.1.2';   jar = 'coreprotect-fabric-26.1.2\build\libs\coreprotect-fabric-26.1.2-1.8.2.jar';   notes = 'release-v182-26.1.2.md';  prev = 'v1.8.1-mc26.1.2' }
)

foreach ($t in $targets) {
    $jarPath = Join-Path $root $t.jar
    if (-not (Test-Path $jarPath)) { Write-Output "MISSING JAR: $jarPath"; continue }
    $body = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot $t.notes), [System.Text.Encoding]::UTF8)
    $name = "CoreProtect Fabric v1.8.2 (Minecraft $($t.ver))"

    $existing = $null
    try { $existing = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$($t.tag)" -Headers $headers } catch { $existing = $null }

    if ($existing) {
        $patch = @{ body = $body; name = $name } | ConvertTo-Json -Depth 4
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$($existing.id)" -Headers $headers -Method Patch -Body ([System.Text.Encoding]::UTF8.GetBytes($patch)) -ContentType 'application/json; charset=utf-8'
        Write-Output "updated release $($t.tag) (id $($rel.id))"
    } else {
        $payload = @{ tag_name = $t.tag; target_commitish = 'main'; name = $name; body = $body; draft = $false; prerelease = $false } | ConvertTo-Json -Depth 4
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Headers $headers -Method Post -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) -ContentType 'application/json; charset=utf-8'
        Write-Output "created release $($t.tag) (id $($rel.id))"
    }

    $assetName = [System.IO.Path]::GetFileName($jarPath)
    $already = $rel.assets | Where-Object { $_.name -eq $assetName }
    if ($already) {
        Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/assets/$($already.id)" -Headers $headers -Method Delete | Out-Null
    }
    $bytes = [System.IO.File]::ReadAllBytes($jarPath)
    $up = Invoke-RestMethod -Uri "https://uploads.github.com/repos/$repo/releases/$($rel.id)/assets?name=$assetName" -Headers $headers -Method Post -Body $bytes -ContentType 'application/java-archive'
    Write-Output ("  uploaded {0} ({1} MB)" -f $up.name, [math]::Round($up.size / 1MB, 1))
    Write-Output "  url: $($rel.html_url)"

    # point the previous release at this one
    try {
        $prevRel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$($t.prev)" -Headers $headers
        $prefix = "> [!IMPORTANT]`n> Superseded by **$($t.tag)** (v1.8.2), which fixes a set of logging, rollback and database-safety bugs. Please use the newer release.`n> 已被 **$($t.tag)**（v1.8.2）取代，修复了记录、回滚与数据库安全方面的一批缺陷，请使用新版本。`n`n"
        if ($prevRel.body -notlike "*$($t.tag)*") {
            $patch = @{ body = $prefix + $prevRel.body } | ConvertTo-Json -Depth 4
            Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$($prevRel.id)" -Headers $headers -Method Patch -Body ([System.Text.Encoding]::UTF8.GetBytes($patch)) -ContentType 'application/json; charset=utf-8' | Out-Null
            Write-Output "  noted on previous release $($t.prev)"
        }
    } catch {
        Write-Output "  (previous release $($t.prev) not updated: $($_.Exception.Message))"
    }
}
Write-Output 'publish done'
