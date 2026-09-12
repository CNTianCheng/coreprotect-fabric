$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$template = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release-v182-template.md'), [System.Text.Encoding]::UTF8)

$targets = @(
    @{ ver = '1.21';    java = '21'; loader = '0.16' },
    @{ ver = '1.21.11'; java = '21'; loader = '0.16' },
    @{ ver = '26.1.2';  java = '25'; loader = '0.19' }
)
foreach ($t in $targets) {
    $body = $template.Replace('@VER@', $t.ver).Replace('@JAVA@', $t.java).Replace('@LOADER@', $t.loader)
    $out = Join-Path $PSScriptRoot ("release-v182-" + $t.ver + ".md")
    [System.IO.File]::WriteAllText($out, $body, $utf8)
    Write-Host "wrote $out"
}
