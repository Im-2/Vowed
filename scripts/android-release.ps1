# Builds the release APK (signed when ~/.vowed-signing/signing.properties exists), then prints its path, size and SHA-256.
# Lint runs in full. If the first (online) try fails on a network download, it retries with --offline, which works once one build has cached the
# lint dependencies on this PC (checked: `gradlew --offline lintVitalRelease --rerun` passes).
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "C:\Users\hp\Android\Sdk"
Push-Location "$PSScriptRoot\..\android"
.\gradlew.bat --no-daemon assembleRelease
if ($LASTEXITCODE -ne 0) { "online build failed, retrying offline"; .\gradlew.bat --no-daemon --offline assembleRelease }
$code = $LASTEXITCODE
Pop-Location
if ($code -eq 0) {
    $apk = Get-ChildItem "$PSScriptRoot\..\android\app\build\outputs\apk\release\*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    "APK:    $($apk.FullName)"
    "size:   $($apk.Length) bytes ($([math]::Round($apk.Length/1MB,1)) MB)"
    "sha256: $((Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLower())"
}
exit $code
