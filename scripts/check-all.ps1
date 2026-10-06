# One-command check: backend tests, Android debug build, Anchor program build+test (via WSL).
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\.."
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "C:\Users\hp\Android\Sdk"
Push-Location "$root\backend"; npm run typecheck; npm test; Pop-Location
Push-Location "$root\android"; .\gradlew.bat assembleDebug; Pop-Location
$wslPath = "/mnt/" + $root.Path.Substring(0,1).ToLower() + ($root.Path.Substring(2) -replace '\','/')
wsl -d Ubuntu -u root -- bash "$wslPath/scripts/program-build.sh"
