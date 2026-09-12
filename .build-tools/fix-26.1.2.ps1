$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir = Join-Path $root 'coreprotect-fabric-26.1.2\src\main\java'

$pairs = @(
    @('WorldMixin', '@@WM@@'),
    @('SignText', '@@SIGNTEXT@@'),
    @('import net.minecraft.block.entity.@@SIGNTEXT@@;', 'import net.minecraft.world.level.block.entity.SignText;'),
    @('@@SIGNTEXT@@', 'SignText'),
    @('.getServerLevel()', '.level()'),
    @('List<Text>', 'List<Component>'),
    @('for (Text ', 'for (Component '),
    @('static Text ', 'static Component '),
    @('Text text)', 'Component text)'),
    @('Formatting tagColor', 'ChatFormatting tagColor'),
    @('World ', 'Level '),
    @('(World)', '(Level)'),
    @('@@WM@@', 'WorldMixin')
)

$files = Get-ChildItem -Path $dir -Recurse -File -Filter *.java
foreach ($f in $files) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    $orig = $text
    foreach ($p in $pairs) { $text = $text.Replace($p[0], $p[1]) }
    # always rewrite: strips BOM even when no other change was needed
    [System.IO.File]::WriteAllText($f.FullName, $text, (New-Object System.Text.UTF8Encoding($false)))
    if ($text -ne $orig) { Write-Host ("  patched " + $f.Name) }
}
Write-Host "cleanup pass done"
