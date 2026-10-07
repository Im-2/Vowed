# Phase 6 gate, part 1: alice connects, grants notifications, creates a squad and prints its invite code.
. "$PSScriptRoot\emu-lib.ps1"
Reset-And-Connect
& $script:adb shell pm grant app.vowed android.permission.POST_NOTIFICATIONS 2>&1 | Out-Null
"squads tab: " + (Tap-Text "Squads" 20)
"squads screen: " + (Has-Text "Create a squad" 20)
"name: " + (Set-Field "Squad name" "Gym%sCrew")
"create: " + (Tap-Text "Create" 10)
"detail: " + (Has-Text "Leaderboard" 30)
Screen-Texts | Select-Object -First 20
