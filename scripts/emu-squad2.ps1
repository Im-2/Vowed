# Phase 6 gate, part 2: alice opens the squad and starts a squad challenge from a plain-words goal.
. "$PSScriptRoot\emu-lib.ps1"
if (-not $env:SKIP_RESET) { Reset-And-Connect }
& $script:adb shell pm grant app.vowed android.permission.POST_NOTIFICATIONS 2>&1 | Out-Null
"squads tab: " + (Tap-Text "Squads" 60)
"open squad: " + (Tap-Text "Gym" 30 -Prefix)
"squad detail: " + (Has-Text "Start a challenge for this squad" 30)
Screen-Texts | Select-Object -First 25
"start: " + (Tap-Text "Start a challenge for this squad" 10)
"goal field: " + (Has-Text "Your goal, in your own words" 20)
"type: " + (Set-Field "Your goal, in your own words" "focus%sfor%s25%sminutes%severy%sday%sfor%s3%sdays")
"preview: " + (Tap-Text "Preview plan" 10)
"plan: " + (Has-Text "Your plan" 40)
Screen-Texts | Select-Object -First 30
