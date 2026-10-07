# Phase 6 gate part 4: alice opens the squad challenge from the squad screen and joins it with a stake.
. "$PSScriptRoot\emu-lib.ps1"
& $script:adb shell input keyevent KEYCODE_BACK; Start-Sleep 1
"squads: " + (Tap-Text "Squads" 20); Start-Sleep 2
"open squad: " + (Tap-Text "Gym" 20 -Prefix)
"challenge button: " + (Tap-Text "Open" 20 -Contains)
"detail: " + (Has-Text "Join" 30 -Contains)
Screen-Texts | Select-Object -First 30
