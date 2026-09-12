$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$cred = "protocol=https`nhost=github.com`n" | git credential fill 2>$null
$token = ($cred | Select-String '^password=').ToString().Substring(9)
$headers = @{ 'Authorization' = "Bearer $token"; 'User-Agent' = 'coreprotect-release' }

# update the flagship release body
$rel = Invoke-RestMethod -Uri 'https://api.github.com/repos/CNTianCheng/coreprotect-fabric/releases/tags/v1.7.2' -Headers $headers
$body172 = [System.IO.File]::ReadAllText("$PSScriptRoot\release-v172.md", [System.Text.Encoding]::UTF8)
$patch = @{ body = $body172 } | ConvertTo-Json -Depth 4
Invoke-RestMethod -Uri "https://api.github.com/repos/CNTianCheng/coreprotect-fabric/releases/$($rel.id)" -Headers $headers -Method Patch -Body ([System.Text.Encoding]::UTF8.GetBytes($patch)) | Out-Null
Write-Output 'v1.7.2 body updated (bilingual)'

$template = [System.IO.File]::ReadAllText("$PSScriptRoot\release-v100-template.md", [System.Text.Encoding]::UTF8)
# non-ASCII text must come from UTF-8 data files: Windows PowerShell reads .ps1 as ANSI
$noteEn = ([System.IO.File]::ReadAllText("$PSScriptRoot\repair\note-en.txt", [System.Text.Encoding]::UTF8)).Trim()
$noteZh = ([System.IO.File]::ReadAllText("$PSScriptRoot\repair\note-zh.txt", [System.Text.Encoding]::UTF8)).Trim()
$versions = @(
    @{ mc = '1.21.1';  tag = 'v1.0.0-mc1.21.1';  java = '21'; mojmap = $false },
    @{ mc = '1.21.2';  tag = 'v1.0.0-mc1.21.2';  java = '21'; mojmap = $false },
    @{ mc = '1.21.3';  tag = 'v1.0.0-mc1.21.3';  java = '21'; mojmap = $false },
    @{ mc = '1.21.4';  tag = 'v1.0.0-mc1.21.4';  java = '21'; mojmap = $false },
    @{ mc = '1.21.5';  tag = 'v1.0.0-mc1.21.5';  java = '21'; mojmap = $false },
    @{ mc = '1.21.6';  tag = 'v1.0.0-mc1.21.6';  java = '21'; mojmap = $false },
    @{ mc = '1.21.7';  tag = 'v1.0.0-mc1.21.7';  java = '21'; mojmap = $false },
    @{ mc = '1.21.8';  tag = 'v1.0.0-mc1.21.8';  java = '21'; mojmap = $false },
    @{ mc = '1.21.9';  tag = 'v1.0.0-mc1.21.9';  java = '21'; mojmap = $false },
    @{ mc = '1.21.10'; tag = 'v1.0.0-mc1.21.10'; java = '21'; mojmap = $false },
    @{ mc = '1.21.11'; tag = 'v1.0.0-mc1.21.11'; java = '21'; mojmap = $false },
    @{ mc = '26.1';    tag = 'v1.0.0-mc26.1';    java = '25'; mojmap = $true },
    @{ mc = '26.1.1';  tag = 'v1.0.0-mc26.1.1';  java = '25'; mojmap = $true },
    @{ mc = '26.1.2';  tag = 'v1.0.0-mc26.1.2';  java = '25'; mojmap = $true },
    @{ mc = '26.2';    tag = 'v1.0.0-mc26.2';    java = '25'; mojmap = $true }
)
$i = 0
foreach ($v in $versions) {
    $i++
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/CNTianCheng/coreprotect-fabric/releases/tags/$($v.tag)" -Headers $headers
    $jarName = $rel.assets[0].name
    $body = $template.Replace('{mc}', $v.mc).Replace('{java}', $v.java).Replace('{jar}', $jarName)
    if ($v.mojmap) {
        $body = $body.Replace('{mapping_note_en}', $noteEn).Replace('{mapping_note_zh}', $noteZh)
    } else {
        $body = $body.Replace('{mapping_note_en}', '').Replace('{mapping_note_zh}', '')
    }
    $patch = @{ body = $body } | ConvertTo-Json -Depth 4
    Invoke-RestMethod -Uri "https://api.github.com/repos/CNTianCheng/coreprotect-fabric/releases/$($rel.id)" -Headers $headers -Method Patch -Body ([System.Text.Encoding]::UTF8.GetBytes($patch)) | Out-Null
    Write-Output "[$i/15] OK $($v.tag) body updated (bilingual)"
}
Write-Output 'all done'
