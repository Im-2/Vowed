# One clean real-Phantom run on the emulator: reset Phantom, warm it, sign in to Vowed, then create-and-stake. Logs every step; screenshots of Phantom's sheets
# go to %TEMP%. Needs the emulator (vowed_play) with Phantom set up (docs/emulator-real-wallet-testing.md). Usage: powershell scripts/emu-phantom-e2e.ps1
. "$PSScriptRoot\emu-lib.ps1"
$adb = "C:\Users\hp\Android\Sdk\platform-tools\adb.exe"
function Say($m) { "$((Get-Date).ToString('HH:mm:ss')) $m" }
function Approve($secs, $taps) { powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\emu-phantom-approve.ps1" -Seconds $secs -MaxTaps $taps }
function Launch-Vowed { & $adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.vowed/.MainActivity | Out-Null }

Say "reset: force-stop Phantom and Vowed"
& $adb shell am force-stop app.phantom; & $adb shell am force-stop app.vowed; Start-Sleep 2
& $adb shell monkey -p app.phantom -c android.intent.category.LAUNCHER 1 | Out-Null
for ($i = 0; $i -lt 25; $i++) { Start-Sleep 4; if (((Screen-Texts) -join '|') -match 'Search Phantom') { break } }
Start-Sleep 25; & $adb shell input keyevent 3; Start-Sleep 2
Say "Phantom warm; opening Vowed"
Launch-Vowed; Start-Sleep 8

if (Has-Text "Get started" 5) {
    Say "sign-in"
    Tap-Text "Get started" 20 | Out-Null; Has-Text "Connect wallet" 20 | Out-Null; Tap-Text "Connect wallet" 20 | Out-Null; Has-Text "Wallet setup" 20 | Out-Null; Start-Sleep 2
    & $adb shell input tap 540 2305
    Approve 150 2 | ForEach-Object { Say $_ }
    Start-Sleep 12
}
$t = (Screen-Texts) -join '|'
Say "after sign-in: $($t.Substring(0, [Math]::Min(80, $t.Length)))"
if ($t -notmatch 'Start your first challenge|Today') { Say "SIGN-IN DID NOT COMPLETE"; exit 1 }

Say "stake: new challenge"
Tap-Text "Start your first challenge" 15 | Out-Null; Start-Sleep 4
& $adb shell input tap 540 560; Start-Sleep 1; & $adb shell input text "Read%sfor%s30%sminutes%sa%sday"; Start-Sleep 1; & $adb shell input keyevent 4; Start-Sleep 1
& $adb shell input tap 540 1093; Has-Text "Your plan" 40 | Out-Null; Start-Sleep 2
1..4 | ForEach-Object { & $adb shell input swipe 540 1900 540 400 300; Start-Sleep -Milliseconds 600 }
& $adb shell input tap 540 2268; Has-Text "Check before you sign" 40 | Out-Null; Start-Sleep 2
1..3 | ForEach-Object { & $adb shell input swipe 540 1900 540 500 300; Start-Sleep -Milliseconds 600 }
& $adb logcat -c
Say "tap Sign with wallet"
& $adb shell input tap 540 2100
Approve 200 2 | ForEach-Object { Say $_ }
for ($i = 0; $i -lt 25; $i++) { Start-Sleep 4; $t = (Screen-Texts) -join '|'; if ($t -notmatch 'Checking whether|Confirming|Sending your signed|Working') { break } }
Say "result: $(($t -split '\|' | Select-Object -Last 3) -join ' | ')"

# what a person does when Phantom skips a sheet: tap Try again, check the new review, tap Sign again (at most twice)
for ($retry = 1; $retry -le 2 -and $t -match 'Try again'; $retry++) {
    Say "retry ${retry}: Try again"
    Tap-Text "Try again" 10 | Out-Null; Start-Sleep 6
    Tap-Text "Review" 10 | Out-Null; Has-Text "Check before you sign" 40 | Out-Null; Start-Sleep 2
    1..3 | ForEach-Object { & $adb shell input swipe 540 1900 540 500 300; Start-Sleep -Milliseconds 600 }
    Say "tap Sign with wallet (retry $retry)"
    & $adb shell input tap 540 2100
    Approve 200 3 | ForEach-Object { Say $_ }
    for ($i = 0; $i -lt 25; $i++) { Start-Sleep 4; $t = (Screen-Texts) -join '|'; if ($t -notmatch 'Checking whether|Confirming|Sending your signed|Working') { break } }
    Say "result after retry ${retry}: $(($t -split '\|' | Select-Object -Last 3) -join ' | ')"
}

