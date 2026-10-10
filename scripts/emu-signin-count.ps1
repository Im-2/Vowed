# Dev tool: first sign-in on the emulator with the Mock wallet, counting how many wallet screens (activities of com.solana.mwallet) appear.
# Assumes the app data is clear and backend_url points at the dev backend (see docs/device-tests.md). Prints the wallet sheets it saw and the final state.
param([int]$TimeoutSec = 150)
. "$PSScriptRoot\emu-lib.ps1"
$sheets = New-Object System.Collections.Generic.List[string]
function Note-Wallet {
    $f = Focus
    if ($f -match "com.solana.mwallet") {
        $name = ($f -replace '.*com.solana.mwallet/', '' -replace '\}.*', '')
        if ($sheets.Count -eq 0 -or $sheets[$sheets.Count - 1] -ne $name) { $sheets.Add($name); "  wallet screen: $name" }
    }
}
"onboarding: " + (Has-Text "Get started" 60)
Tap-Text "Get started" 10 | Out-Null; Start-Sleep 2
"connect tap: " + (Tap-Text "Connect wallet" 30)
Start-Sleep 2
if (Find-Node "connect now" -Contains) { "  first-time wallet setup screen shown; tapping connect now"; Tap-Text "connect now" 5 -Contains | Out-Null }
$end = (Get-Date).AddSeconds($TimeoutSec)
$done = $false
$taps = 0
while ((Get-Date) -lt $end -and -not $done) {
    Note-Wallet
    Pin-If-Asked
    foreach ($t in @("Approve", "Connect", "Allow")) {
        if ((Focus) -match "mwallet" -or $t -eq "Allow") {
            if (Find-Node $t) { Tap-Text $t 1 | Out-Null; "  tapped $t"; Start-Sleep 2 }
        }
    }
    if (Find-Node "Wallet connected") { $done = $true }
    if ((Find-Node "Try again") -and $taps -lt 3 -and -not ((Focus) -match "mwallet")) { $taps++; "  user taps Try again (tap $taps): " + ((Screen-Texts | Where-Object { $_ -like "Your wallet*" -or $_ -like "Wallet approved*" -or $_ -like "Registering*" -or $_ -like "Vowed could*" }) -join " | "); Tap-Text "Try again" 2 | Out-Null; Start-Sleep 3; continue }
    if (Find-Node "Dismiss") { "  ERROR SHOWN: " + ((Screen-Texts | Select-Object -First 12) -join " | "); $done = $true }
}
"wallet screens counted: " + $sheets.Count
"result: " + ((Screen-Texts | Select-Object -First 8) -join " | ")

