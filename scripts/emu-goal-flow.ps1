# Dev tool: fresh wallet connection, then goal example -> plan preview -> review -> sign create -> sign join -> pool detail, all inside
# the Mock wallet's short authentication window. Usage: emu-goal-flow.ps1 [-Example "Do 20 squats every day"]
param([string]$Example = "Do 20 squats every day")
. "$PSScriptRoot\emu-lib.ps1"
Reset-And-Connect
"example chip: " + (Tap-Text $Example 20)
"plan: " + (Has-Text "Your plan" 40)
& $script:adb shell input swipe 540 1900 540 500 300; Start-Sleep 1; & $script:adb shell input swipe 540 1900 540 500 300; Start-Sleep 2
"review: " + (Tap-Text "Review" 20)
Sign-Reviewed "Create a DEMO pool" 60
Sign-Reviewed "Join and stake" 120
"detail: " + (Has-Text "Your days" 90)
(Get-Date -Format HH:mm:ss)
Screen-Texts | Select-Object -First 14
