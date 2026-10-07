# Phase 6 gate part 5: sign in again after an app reinstall, open the squad, show leaderboard and feed.
. "$PSScriptRoot\emu-lib.ps1"
& $script:adb shell am start -n app.vowed/.MainActivity | Out-Null
"sign in: " + (Tap-Text "Sign in" 40)
$end = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $end -and -not (Has-Text "New challenge" 2)) { if (Tap-Text "Allow" 1) { "  allowed" }; if (Tap-Text "Approve" 4) { "  approved"; Start-Sleep 3 } }
"squads: " + (Tap-Text "Squads" 20); Start-Sleep 2
"open: " + (Tap-Text "Gym" 20 -Prefix)
Start-Sleep 10
Screen-Texts | Select-Object -First 40
& $script:adb exec-out screencap -p > "$PSScriptRoot\..\docs\phase6-squad-feed.png"
