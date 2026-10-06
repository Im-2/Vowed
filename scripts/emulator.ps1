# Start the emulator and install debug builds of the Mock MWA Wallet and Vowed.
$sdk = "C:\Users\hp\Android\Sdk"
Start-Process "$sdk\emulator\emulator.exe" -ArgumentList "-avd","vowed_api36","-no-snapshot","-no-audio","-gpu","swiftshader_indirect"
& "$sdk\platform-tools\adb.exe" wait-for-device
& "$sdk\platform-tools\adb.exe" install -r C:\Users\hp\Android\tools\mock-mwa-wallet\app\build\outputs\apk\debug\app-debug.apk
& "$sdk\platform-tools\adb.exe" install -r "$PSScriptRoot\..\android\app\build\outputs\apk\debug\app-debug.apk"
