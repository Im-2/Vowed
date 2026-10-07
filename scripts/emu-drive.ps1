# Drives the Vowed emulator through create -> join (demo pool) by finding buttons by text. Dev tool only.
param([string]$Steps = "create,join")
$adb = "C:\Users\hp\Android\Sdk\platform-tools\adb.exe"

function Texts {
    & $adb shell uiautomator dump /sdcard/u.xml 2>&1 | Out-Null
    [xml](& $adb shell cat /sdcard/u.xml)
}
function Tap-Text($t, $timeoutSec = 60) {
    $end = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $end) {
        try {
            $n = (Texts).SelectNodes("//node[@text='$t']") | Select-Object -First 1
            if ($n) {
                $b = $n.bounds -replace '[\[\]]', ' ' -split '\s+' | Where-Object { $_ }
                $x = [int](([int]($b[0].Split(',')[0]) + [int]($b[1].Split(',')[0])) / 2)
                $y = [int](([int]($b[0].Split(',')[1]) + [int]($b[1].Split(',')[1])) / 2)
                & $adb shell input tap $x $y
                return $true
            }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    return $false
}
function Has-Text($t, $timeoutSec = 60) {
    $end = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $end) {
        try { if ((Texts).SelectNodes("//node[@text='$t']").Count -gt 0) { return $true } } catch { }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

if ($Steps -match "create") {
    "review: " + (Tap-Text "Review" 20)
    "create review shown: " + (Has-Text "Create a DEMO pool" 30)
    "sign: " + (Tap-Text "Sign with wallet" 10)
    "wallet approve: " + (Tap-Text "Approve" 90)
}
if ($Steps -match "join") {
    "join review shown: " + (Has-Text "Join and stake" 90)
    "sign: " + (Tap-Text "Sign with wallet" 10)
    "wallet approve: " + (Tap-Text "Approve" 90)
}
