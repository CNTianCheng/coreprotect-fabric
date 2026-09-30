# Generates the v1.9.0 release notes for 1.21.11 and 26.1.2 from the 1.21 note.
# Only ASCII literals live in this script; the translated text is copied verbatim
# from the UTF-8 source file, which is why the replacements use ASCII anchors only.
$ErrorActionPreference = 'Stop'
$src = Join-Path $PSScriptRoot 'release-v190-1.21.md'
$text = [System.IO.File]::ReadAllText($src, [System.Text.Encoding]::UTF8)

$flagshipNote = '> **Flagship build**: same commands, logging coverage, colours, crash/power-loss protection, bStats metrics and update checks as the 1.21.11 and 26.1.2 builds, now on database schema v3.'
$peerNote = '> **Feature set identical to the 1.21 flagship build**: same commands, logging coverage, colours, crash/power-loss protection, bStats metrics and update checks, now on database schema v3.'

function New-Note([string]$mc, [string]$java, [string]$jar, [string]$intro) {
    $t = $text
    $t = $t.Replace('# CoreProtect Fabric v1.9.0 (Minecraft 1.21)', "# CoreProtect Fabric v1.9.0 (Minecraft $mc)")
    $t = $t.Replace('for the Minecraft 1.21 build; the same change ships for 1.21.11 and 26.1.2.', $intro)
    $t = $t.Replace($flagshipNote, $peerNote)
    $t = $t.Replace('coreprotect-fabric-1.21-1.9.0.jar', $jar)
    $t = $t.Replace('Requirements: Minecraft **1.21**, Java **21**', "Requirements: Minecraft **$mc**, Java **$java**")
    $t = $t.Replace('Java **21**, Fabric Loader', "Java **$java**, Fabric Loader")
    $t = $t.Replace('**1.21**' + [char]0x3001 + 'Java **21**', "**$mc**" + [char]0x3001 + "Java **$java**")
    return $t
}

$out1 = New-Note '1.21.11' '21' 'coreprotect-fabric-1.21.11-1.9.0.jar' 'for the Minecraft 1.21.11 build; the same change ships for 1.21 and 26.1.2.'
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'release-v190-1.21.11.md'), $out1, (New-Object System.Text.UTF8Encoding($false)))

$out2 = New-Note '26.1.2' '25' 'coreprotect-fabric-26.1.2-1.9.0.jar' 'for the Minecraft 26.1.2 build; the same change ships for 1.21 and 1.21.11.'
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'release-v190-26.1.2.md'), $out2, (New-Object System.Text.UTF8Encoding($false)))

foreach ($f in 'release-v190-1.21.md', 'release-v190-1.21.11.md', 'release-v190-26.1.2.md') {
    $p = Join-Path $PSScriptRoot $f
    $body = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
    $jar = ([regex]::Match($body, 'coreprotect-fabric-[0-9.]+-1\.9\.0\.jar')).Value
    $mc = ([regex]::Match($body, 'Requirements: Minecraft \*\*([0-9.]+)\*\*, Java \*\*([0-9]+)\*\*'))
    Write-Output ("{0}: {1} chars, jar={2}, requirements={3}/{4}, lines={5}" -f $f, $body.Length, $jar, $mc.Groups[1].Value, $mc.Groups[2].Value, ($body -split "`n").Count)
}
Write-Output 'notes generated'
