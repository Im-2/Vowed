# Against the HOSTED backend: connect, claim test tokens, create a public challenge from plain words, join, find it in Explore.
. "$PSScriptRoot\emu-lib.ps1"
Reset-And-Connect
"backend shown: " + ((Screen-Texts | Where-Object { $_ -like "*onrender*" }) -join ",")
Screen-Texts | Where-Object { $_ -like "You have*" -or $_ -like "*claim*" -or $_ -like "Claim*" } | Select-Object -First 4
if (Tap-Text "Claim" 5 -Prefix) { "faucet tapped"; Start-Sleep 12; Screen-Texts | Where-Object { $_ -like "You have*" -or $_ -like "*claim*" } | Select-Object -First 3 }
"new: " + (Tap-Text "New challenge" 20)
"field: " + (Has-Text "Your goal, in your own words" 20)
"type: " + (Set-Field "Your goal, in your own words" "I%swant%sto%spractise%sguitar%sfor%s45%sminutes%seach%sevening%sfor%s3%sdays")
"preview: " + (Tap-Text "Preview plan" 10)
"plan: " + (Has-Text "Your plan" 90)
Screen-Texts | Where-Object { $_ -like "*AI*" -or $_ -like "Built-in*" -or $_ -like "Understood*" } | Select-Object -First 3
"10 min: " + (Tap-Text "10 min days" 10)
& $script:adb shell input swipe 540 1900 540 500 300; Start-Sleep 1
"public: " + (Tap-Text "Public (listed in Explore)" 10)
& $script:adb shell input swipe 540 1900 540 500 300; Start-Sleep 1; & $script:adb shell input swipe 540 1900 540 500 300; Start-Sleep 2
"review: " + (Tap-Text "Review" 20)
Sign-Reviewed "Create a DEMO pool" 60
Sign-Reviewed "Join and stake" 120
"detail: " + (Has-Text "Your days" 90)
"explore tab: " + (Tap-Text "Explore" 20); Start-Sleep 8
Screen-Texts | Select-Object -First 30
& $script:adb exec-out screencap -p > "$PSScriptRoot\..\docs\phase7-hosted-explore.png"
"DONE"
