# Rebuilds and re-tests the three full-feature builds, one at a time (RAM is tight).
# NOTE: the test scripts are invoked with literal named parameters - splatting an array
# into them fails binding with "A positional parameter cannot be found".
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'

Write-Output '##### BUILD coreprotect-fabric-1.21 #####'
$env:JAVA_HOME = Join-Path $root '.build-tools\jdk\jdk-21.0.12.1+1'
Push-Location (Join-Path $root 'coreprotect-fabric-1.21')
& (Join-Path $root '.build-tools\gradle\gradle-8.10.2\bin\gradle.bat') build --no-daemon --console=plain 2>&1 |
    Select-String -Pattern 'error:|BUILD ' | Select-Object -First 6
Pop-Location

& (Join-Path $root '.build-tools\test-full.ps1') -WorkDir (Join-Path $root 'coreprotect-fabric-1.21') `
    -Gradle (Join-Path $root '.build-tools\gradle\gradle-8.10.2\bin\gradle.bat') -Jdk 'jdk-21.0.12.1+1' -Fresh 2>&1 |
    Select-String -Pattern '===|rows found|No results|Rollback complete|Restore complete|Purge complete|MIXIN_FAILURES|CORE_ENABLED|FULL_TEST' |
    Select-Object -Last 40

Write-Output '##### BUILD coreprotect-fabric-1.21.11 #####'
$env:JAVA_HOME = Join-Path $root '.build-tools\jdk\jdk-21.0.12.1+1'
Push-Location (Join-Path $root 'coreprotect-fabric-1.21.11')
& (Join-Path $root '.build-tools\gradle\gradle-9.7.1\bin\gradle.bat') build --no-daemon --console=plain 2>&1 |
    Select-String -Pattern 'error:|BUILD ' | Select-Object -First 6
Pop-Location

& (Join-Path $root '.build-tools\test-quick.ps1') -WorkDir (Join-Path $root 'coreprotect-fabric-1.21.11') `
    -Gradle (Join-Path $root '.build-tools\gradle\gradle-9.7.1\bin\gradle.bat') -Jdk 'jdk-21.0.12.1+1' 2>&1 |
    Select-String -Pattern '===|rows found|No results|MIXIN_FAILURES|CORE_ENABLED|QUICK_TEST' |
    Select-Object -Last 30

Write-Output '##### BUILD coreprotect-fabric-26.1.2 #####'
$env:JAVA_HOME = 'C:\Program Files\Java\graalvm-jdk-25.0.2+10.1'
Push-Location (Join-Path $root 'coreprotect-fabric-26.1.2')
& (Join-Path $root '.build-tools\gradle\gradle-9.7.1\bin\gradle.bat') build --no-daemon --console=plain 2>&1 |
    Select-String -Pattern 'error:|BUILD ' | Select-Object -First 6
Pop-Location

& (Join-Path $root '.build-tools\test-full.ps1') -WorkDir (Join-Path $root 'coreprotect-fabric-26.1.2') `
    -Gradle (Join-Path $root '.build-tools\gradle\gradle-9.7.1\bin\gradle.bat') -Jdk 'jdk-25.0.2+10.1' -Fresh 2>&1 |
    Select-String -Pattern '===|rows found|No results|Rollback complete|Restore complete|Purge complete|MIXIN_FAILURES|CORE_ENABLED|FULL_TEST' |
    Select-Object -Last 40

Write-Output 'ALL_BUILDS_AND_TESTS_DONE'
