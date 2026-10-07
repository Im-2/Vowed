# Phase 6 gate, part 3: review and sign the squad challenge (create, then join with a stake).
. "$PSScriptRoot\emu-lib.ps1"
& $script:adb shell input swipe 540 1900 540 500 300; Start-Sleep 1; & $script:adb shell input swipe 540 1900 540 500 300; Start-Sleep 2
"squad note: " + (Has-Text "This is a squad challenge" 5 -Prefix)
"review: " + (Tap-Text "Review" 20)
Sign-Reviewed "Create a DEMO pool" 60
Sign-Reviewed "Join and stake" 120
"detail: " + (Has-Text "Your days" 90)
Screen-Texts | Select-Object -First 16
