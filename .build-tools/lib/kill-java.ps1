# Shared by the test-*.ps1 helpers: validate -WorkDir, then stop only the server process that
# was started from exactly that directory.
#
#   . (Join-Path $PSScriptRoot 'lib\kill-java.ps1')
#   $WorkDir = Assert-WorkDir -WorkDir $WorkDir -Root $root
#   Stop-StaleJava -WorkDir $WorkDir
#
# Both functions replace what every test script used to inline: an unvalidated -WorkDir plus
# `CommandLine -like "*$WorkDir*"`. A wildcard also matched a directory that merely contained
# the string (an empty -WorkDir matched every java.exe), so one typo could kill an unrelated
# server - and Stop-Process -Force made that unrecoverable. test-full.ps1 already did it right;
# this file is that logic, so the other helpers stop carrying their own copy.
#
# ASCII only: check-ascii.ps1 rejects any non-ASCII byte under .build-tools.

function Assert-WorkDir {
    # Resolves -WorkDir against the repository root and refuses anything outside it, so the
    # process filter below can never be pointed at a directory the mod does not own.
    param(
        [Parameter(Mandatory = $true)][string]$WorkDir,
        [Parameter(Mandatory = $true)][string]$Root
    )
    if (-not [System.IO.Path]::IsPathRooted($WorkDir)) { $WorkDir = Join-Path $Root $WorkDir }
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    $WorkDir = [System.IO.Path]::GetFullPath($WorkDir).TrimEnd('\')
    if (-not $WorkDir.StartsWith($rootFull + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "invalid -WorkDir '$WorkDir': must be a directory inside $rootFull"
    }
    if (-not (Test-Path -LiteralPath $WorkDir)) { throw "missing -WorkDir '$WorkDir'" }
    return $WorkDir
}

function Stop-StaleJava {
    # Kills only java.exe whose command line contains this exact work directory, compared with
    # IndexOf (no wildcards). Does nothing when no such process is running.
    param(
        [Parameter(Mandatory = $true)][string]$WorkDir,
        [int]$SettleSeconds = 5
    )
    $stale = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.IndexOf($WorkDir, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    })
    if ($stale.Count -eq 0) { return }
    foreach ($staleProc in $stale) {
        Stop-Process -Id $staleProc.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Write-Host "killed $($stale.Count) leftover server process(es)"
    Start-Sleep -Seconds $SettleSeconds
}
