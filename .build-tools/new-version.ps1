param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Yarn,
    [Parameter(Mandatory = $true)][string]$FabricApi,
    [Parameter(Mandatory = $true)][string]$Loader,
    [Parameter(Mandatory = $true)][string]$Src
)
# workspace root = parent directory of the .build-tools folder containing this script
$root = Split-Path $PSScriptRoot -Parent
# $Version is interpolated into a directory name that gets deleted below, so it must be a
# plain version segment - never a path fragment that could point outside the repository.
if ($Version -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') {
    throw "invalid -Version '$Version': expected a plain version like 1.21.11"
}
$rootFull = [System.IO.Path]::GetFullPath($root).TrimEnd('\')
$srcDir = [System.IO.Path]::GetFullPath((Join-Path $rootFull $Src))
if (-not $srcDir.StartsWith($rootFull + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "invalid -Src '$Src': outside the repository root"
}
if (-not (Test-Path -LiteralPath $srcDir)) { throw "missing -Src directory '$srcDir'" }
$dest = [System.IO.Path]::GetFullPath((Join-Path $rootFull "coreprotect-fabric-$Version"))
if (-not $dest.StartsWith($rootFull + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "refusing to touch '$dest': outside the repository root"
}
if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
robocopy $srcDir $dest /E /XD .gradle build run /NFL /NDL /NJH /NJS /NP | Out-Null
$gp = Get-Content (Join-Path $dest 'gradle.properties') -Raw
$gp = $gp -replace '(?m)^minecraft_version=.*$', "minecraft_version=$Version"
$gp = $gp -replace '(?m)^yarn_mappings=.*$', "yarn_mappings=$Yarn"
$gp = $gp -replace '(?m)^loader_version=.*$', "loader_version=$Loader"
$gp = $gp -replace '(?m)^fabric_version=.*$', "fabric_version=$FabricApi"
$gp = $gp -replace '(?m)^archives_base_name=.*$', "archives_base_name=coreprotect-fabric-$Version"
[System.IO.File]::WriteAllText((Join-Path $dest 'gradle.properties'), $gp, [System.Text.UTF8Encoding]::new($false))
$fm = Get-Content (Join-Path $dest 'src/main/resources/fabric.mod.json') -Raw
$fm = $fm -replace '"minecraft": "[^"]*"', ('"minecraft": "{0}"' -f $Version)
[System.IO.File]::WriteAllText((Join-Path $dest 'src/main/resources/fabric.mod.json'), $fm, [System.Text.UTF8Encoding]::new($false))
# 1.21.5+ needs a newer Loom (fabric-api is built with it) and Gradle 9
if ([version]$Version -ge [version]'1.21.5') {
    $bg = Join-Path $dest 'build.gradle'
    $c = [System.IO.File]::ReadAllText($bg)
    $c = $c -replace "id 'fabric-loom' version '[^']*'", "id 'fabric-loom' version '1.17.19'"
    [System.IO.File]::WriteAllText($bg, $c, [System.Text.UTF8Encoding]::new($false))
    $wp = Join-Path $dest 'gradle\wrapper\gradle-wrapper.properties'
    $w = [System.IO.File]::ReadAllText($wp)
    $w = $w -replace 'gradle-[0-9.]+-bin\.zip', 'gradle-9.7.1-bin.zip'
    [System.IO.File]::WriteAllText($wp, $w, [System.Text.UTF8Encoding]::new($false))
    Write-Host 'TOOLCHAIN_UPGRADED'
}
Write-Host "PROJECT_READY $dest"
