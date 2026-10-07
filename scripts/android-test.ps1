# Runs the Android JVM unit tests (core safety checks) and builds the debug APK.
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "C:\Users\hp\Android\Sdk"
Push-Location "$PSScriptRoot\..\android"
.\gradlew.bat testDebugUnitTest assembleDebug
$code = $LASTEXITCODE
Pop-Location
exit $code
