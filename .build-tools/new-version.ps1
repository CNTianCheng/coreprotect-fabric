param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$Yarn,
    [Parameter(Mandatory = $true)][string]$FabricApi,
    [Parameter(Mandatory = $true)][string]$Loader,
    [Parameter(Mandatory = $true)][string]$Src
)
# workspace root = parent directory of the .build-tools folder containing this script
$root = Split-Path $PSScriptRoot -Parent
$dest = Join-Path $root "coreprotect-fabric-$Version"
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
robocopy (Join-Path $root $Src) $dest /E /XD .gradle build run /NFL /NDL /NJH /NJS /NP | Out-Null
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
