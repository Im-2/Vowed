# Phase 6 follow-up: squad "Gym Crew", 10-minute demo days, alice's own check-in and the Nudge button.
# Handshake with the bash side through flag files: .code.txt (invite code), .bobjoined (bob is in the squad).
. "$PSScriptRoot\emu-lib.ps1"
Reset-And-Connect
"squads tab: " + (Tap-Text "Squads" 30)
Start-Sleep 3
"open squad: " + (Tap-Text "Gym Crew" 20 -Prefix)
"detail: " + (Has-Text "Start a challenge for this squad" 40)
"start: " + (Tap-Text "Start a challenge for this squad" 10)
"goal field: " + (Has-Text "Your goal, in your own words" 20)
"type: " + (Set-Field "Your goal, in your own words" "read%sfor%s30%sminutes%severy%sday%sfor%s3%sdays")
"preview: " + (Tap-Text "Preview plan" 10)
"plan: " + (Has-Text "Your plan" 40)
"10 min days: " + (Tap-Text "10 min days" 10)
& $script:adb shell input swipe 540 1900 540 500 300; Start-Sleep 1; & $script:adb shell input swipe 540 1900 540 500 300; Start-Sleep 2
"review: " + (Tap-Text "Review" 20)
Sign-Reviewed "Create a DEMO pool" 60
Sign-Reviewed "Join and stake" 120
"detail: " + (Has-Text "Your days" 90)
$url = & $script:adb shell dumpsys activity activities | Select-String "x" | Select-Object -First 0
Screen-Texts | Select-Object -First 12
"DONE-A"
