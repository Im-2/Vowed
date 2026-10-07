. "$PSScriptRoot\emu-lib.ps1"
Reset-And-Connect
Start-Sleep 6
& $script:adb exec-out screencap -p > "$PSScriptRoot\..\docs\ui\07-home.png"
"shot taken"
