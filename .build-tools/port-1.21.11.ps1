$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$src = Join-Path $root 'coreprotect-fabric-1.21\src\main'
$dst = Join-Path $root 'coreprotect-fabric-1.21.11\src\main'

Write-Host "Copying java sources from 1.21 -> 1.21.11"
Copy-Item -Path (Join-Path $src 'java\*') -Destination (Join-Path $dst 'java') -Recurse -Force
Copy-Item -Path (Join-Path $src 'resources\assets\*') -Destination (Join-Path $dst 'resources\assets') -Recurse -Force

# Global yarn 1.21 -> yarn 1.21.11 API replacements
$pairs = @(
    @('getGameProfile().getName()', 'getGameProfile().name()'),
    @('entity.getWorld()', 'entity.getEntityWorld()'),
    @('player.getWorld()', 'player.getEntityWorld()'),
    @('player.getServerWorld()', 'player.getEntityWorld()'),
    @('getWrapperOrThrow(', 'getOrThrow('),
    @('SharedConstants.getGameVersion().getName()', 'SharedConstants.getGameVersion().name()')
)

$files = Get-ChildItem -Path (Join-Path $dst 'java') -Recurse -File -Filter *.java
$changed = 0
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $orig = $text
    foreach ($p in $pairs) { $text = $text.Replace($p[0], $p[1]) }
    if ($text -ne $orig) {
        [System.IO.File]::WriteAllText($f.FullName, $text, (New-Object System.Text.UTF8Encoding($false)))
        $changed++
        Write-Host ("  patched " + $f.Name)
    }
}
Write-Host "Patched $changed files"
