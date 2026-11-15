# Shared jar manifest reader for the release / publish tooling.
#
# Both .build-tools\release.ps1 (GitHub) and .build-tools\modrinth\publish.ps1 (Modrinth) need
# the version that is compiled into a jar, and the minecraft versions that jar declares. Two
# hand-kept copies of the zip-reading code had already drifted apart once, so it lives here.
#
#   . (Join-Path (Split-Path $PSScriptRoot -Parent) 'lib\jar-manifest.ps1')
#   $info = Get-JarModInfo 'coreprotect-fabric-1.21\build\libs\coreprotect-fabric-1.21-1.9.3.jar'
#   $info.Version    # '1.9.3'      - fabric.mod.json "version"
#   $info.Minecraft  # @('1.21')    - fabric.mod.json depends.minecraft, always an array
#
# The minecraft range matters as much as the version: a 1.21 jar uploaded with
# game_versions = ["1.21.11"] is offered to servers it refuses to start on (audit L5-12).

function Get-JarModInfo([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { throw "jar not found: $path" }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($path)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'fabric.mod.json' } | Select-Object -First 1
        if (-not $entry) { throw "$path does not contain fabric.mod.json" }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try {
            $mod = $reader.ReadToEnd() | ConvertFrom-Json
            $modVersion = ([string]$mod.version).Trim()
            # depends.minecraft is either one string or a list of them; both are normalised here.
            $mc = @()
            if ($mod.depends -and $mod.depends.minecraft) {
                $mc = @($mod.depends.minecraft | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
            }
            return [pscustomobject]@{ Version = $modVersion; Minecraft = $mc }
        } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}
