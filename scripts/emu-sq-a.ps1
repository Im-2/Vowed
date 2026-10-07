. "$PSScriptRoot\emu-lib.ps1"
"squads tab: " + (Tap-Text "Squads" 30)
Start-Sleep 2; if (Tap-Text "Allow" 3) { "allowed notifications" }
"name: " + (Set-Field "Squad name" "Gym%sCrew")
"create: " + (Tap-Text "Create" 10)
"detail: " + (Has-Text "Leaderboard" 40)
$t = @(Screen-Texts | Where-Object { $_ -like "Invite code:*" })[0]
"title: " + ((Screen-Texts | Select-Object -First 3) -join " / ")
"CODE=" + (($t -replace "Invite code:\s*", "").Trim())
