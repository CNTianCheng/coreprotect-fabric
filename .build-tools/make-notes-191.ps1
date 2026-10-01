# Expands release-v191-template.md into one notes file per maintained build.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$template = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release-v191-template.md'), [System.Text.Encoding]::UTF8)
$targets = @(
    @{ mc = '1.21';    java = '21' },
    @{ mc = '1.21.11'; java = '21' },
    @{ mc = '26.1.2';  java = '25' }
)
foreach ($t in $targets) {
    $text = $template.Replace('@MC@', $t.mc).Replace('@JAVA@', $t.java)
    if ($text.Contains('@MC@') -or $text.Contains('@JAVA@')) { throw "unexpanded placeholder for $($t.mc)" }
    $out = Join-Path $PSScriptRoot ("release-v191-" + $t.mc + ".md")
    [System.IO.File]::WriteAllText($out, $text, $utf8)
    Write-Host ("wrote {0} ({1} chars)" -f $out, $text.Length)
}
Write-Host 'NOTES_DONE'
