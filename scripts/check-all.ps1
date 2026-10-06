# One-command check: backend tests, Android debug build, Anchor program build+test (via WSL).
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\.."
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "C:\Users\hp\Android\Sdk"
Push-Location "$root\backend"; npm run typecheck; npm test; Pop-Location
Push-Location "$root\android"; .\gradlew.bat assembleDebug; Pop-Location
$wslPath = '/mnt/' + $root.Path.Substring(0,1).ToLower() + $root.Path.Substring(2).Replace([string][char]92, '/')
wsl -d Ubuntu -u root -- bash "$wslPath/scripts/program-build.sh"
if ($LASTEXITCODE -ne 0) { throw "program build/test failed" }
# full backend suite on Linux, including the tests that run the real program in an in-process VM
wsl -d Ubuntu -u root -- bash "$wslPath/scripts/backend-test.sh"
if ($LASTEXITCODE -ne 0) { throw "backend WSL tests failed" }
