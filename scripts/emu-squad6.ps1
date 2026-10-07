# Phase 6 follow-up: squad "Gym Crew", 10-minute demo days, alice's own check-in and the Nudge button.
# Handshake with the bash side through flag files: .code.txt (invite code), .bobjoined (bob is in the squad).
. "$PSScriptRoot\emu-lib.ps1"
Reset-And-Connect
"squads tab: " + (Tap-Text "Squads" 30)
Start-Sleep 2; if (Tap-Text "Allow" 3) { "allowed notifications" }
"name: " + (Set-Field "Squad name" "Gym%sCrew")
"create: " + (Tap-Text "Create" 10)
"detail: " + (Has-Text "Leaderboard" 30)
$t = Screen-Texts | Where-Object { $_ -like "Invite code:*" } | Select-Object -First 1
"squad title: " + ((Screen-Texts | Select-Object -First 3) -join " / ")
$code = ($t -replace "Invite code:\s*", "").Trim()
Set-Content "$PSScriptRoot\.code.txt" $code
$end = (Get-Date).AddSeconds(180)
while (-not (Test-Path "$PSScriptRoot\.bobjoined") -and (Get-Date) -lt $end) { Start-Sleep 2 }
"bob joined flag: " + (Test-Path "$PSScriptRoot\.bobjoined")
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
