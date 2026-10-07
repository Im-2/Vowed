# Dev tool: restart the app, sign in again (the Mock wallet wants a fresh PIN), open the first challenge and claim.
# Answers the wallet's PIN prompt (test PIN 1234 on the emulator) whenever it appears.
$adb = "C:\Users\hp\Android\Sdk\platform-tools\adb.exe"
function Focus { (& $adb shell dumpsys window | Select-String "mCurrentFocus" | Select-Object -First 1).Line }
function Texts { & $adb shell uiautomator dump /sdcard/u.xml 2>&1 | Out-Null; [xml](& $adb shell cat /sdcard/u.xml) }
function Pin-If-Asked {
    if ((Focus) -match "BiometricPrompt") { Start-Sleep 1; & $adb shell input text 1234; Start-Sleep 1; & $adb shell input keyevent 66; "  pin entered"; Start-Sleep 3 }
}
function Tap-Text($t, $timeoutSec = 60, [switch]$Prefix) {
    $end = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $end) {
        Pin-If-Asked
        try {
            $xp = if ($Prefix) { "//node[starts-with(@text,'$t')]" } else { "//node[@text='$t']" }
            $n = (Texts).SelectNodes($xp) | Select-Object -First 1
            if ($n) {
                $b = $n.bounds -replace '[\[\]]', ' ' -split '\s+' | Where-Object { $_ }
                $x = [int](([int]($b[0].Split(',')[0]) + [int]($b[1].Split(',')[0])) / 2)
                $y = [int](([int]($b[0].Split(',')[1]) + [int]($b[1].Split(',')[1])) / 2)
                & $adb shell input tap $x $y
                return $true
            }
        } catch { }
        Start-Sleep -Milliseconds 700
    }
    return $false
}
function Has-Text($t, $timeoutSec = 60, [switch]$Prefix) {
    $end = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $end) {
        Pin-If-Asked
        try {
            $xp = if ($Prefix) { "//node[starts-with(@text,'$t')]" } else { "//node[@text='$t']" }
            if ((Texts).SelectNodes($xp).Count -gt 0) { return $true }
        } catch { }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

& $adb shell pm clear com.solana.mwallet | Out-Null
& $adb shell pm clear app.vowed | Out-Null
& $adb shell am start -n app.vowed/.MainActivity | Out-Null
"onboarding: " + (Has-Text "Next" 120)
Tap-Text "Next" 10 | Out-Null; Start-Sleep 2; Tap-Text "Next" 10 | Out-Null
for ($try = 1; $try -le 3; $try++) {
    "connect tap (try $try): " + (Tap-Text "Connect wallet" 30)
    $ok = Tap-Text "Connect" 100
    "wallet connect sheet: $ok"
    if ($ok) { break }
}
$end = (Get-Date).AddSeconds(300)
while ((Get-Date) -lt $end -and -not (Has-Text "New challenge" 2)) {
    if (Tap-Text "Approve" 4) { "  approved a wallet request"; Start-Sleep 3 }
}
"home again: " + (Has-Text "New challenge" 30)
"open first card: " + (Tap-Text "20 squats a day" 20)
"claim button: " + (Tap-Text "Claim " 60 -Prefix)
"review: " + (Has-Text "Claim your payout" 60)
"sign: " + (Tap-Text "Sign with wallet" 20)
"approve: " + (Tap-Text "Approve" 120)
Start-Sleep 20
(Texts).SelectNodes("//node[@text!='']") | Select-Object -First 16 | ForEach-Object { $_.text }
