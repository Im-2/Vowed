# Dev helpers for driving the emulator with adb + uiautomator (dot-source this file). Test PIN on the emulator: 1234.
$script:adb = "C:\Users\hp\Android\Sdk\platform-tools\adb.exe"

function Focus { (& $script:adb shell dumpsys window | Select-String "mCurrentFocus" | Select-Object -First 1).Line }
function Texts { & $script:adb shell uiautomator dump /sdcard/u.xml 2>&1 | Out-Null; [xml](& $script:adb shell cat /sdcard/u.xml) }

function Pin-If-Asked {
    if ((Focus) -match "BiometricPrompt") {
        Start-Sleep 1; & $script:adb shell input text 1234; Start-Sleep 1; & $script:adb shell input keyevent 66
        "  pin entered"; Start-Sleep 3
    }
}

function Find-Node($t, [switch]$Prefix, [switch]$Contains) {
    $xp = if ($Prefix) { "//node[starts-with(@text,'$t')]" } elseif ($Contains) { "//node[contains(@text,'$t')]" } else { "//node[@text='$t']" }
    (Texts).SelectNodes($xp) | Select-Object -First 1
}

function Center($n) {
    $b = $n.bounds -replace '[\[\]]', ' ' -split '\s+' | Where-Object { $_ }
    $a = $b[0].Split(','); $c = $b[1].Split(',')
    @([int](([int]$a[0] + [int]$c[0]) / 2), [int](([int]$a[1] + [int]$c[1]) / 2))
}

function Tap-Text($t, $timeoutSec = 60, [switch]$Prefix, [switch]$Contains) {
    $end = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $end) {
        Pin-If-Asked
        try {
            $n = Find-Node $t -Prefix:$Prefix -Contains:$Contains
            if ($n) { $c = Center $n; & $script:adb shell input tap $c[0] $c[1]; return $true }
        } catch { }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

function Has-Text($t, $timeoutSec = 60, [switch]$Prefix, [switch]$Contains) {
    $end = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $end) {
        Pin-If-Asked
        try { if (Find-Node $t -Prefix:$Prefix -Contains:$Contains) { return $true } } catch { }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

function Screen-Texts { (Texts).SelectNodes("//node[@text!='']") | ForEach-Object { $_.text } }

function Set-Field($label, $value) {
    # tap the text field whose label text is shown on screen, clear it, type the value
    $n = Find-Node $label -Contains
    if (-not $n) { return $false }
    $c = Center $n
    & $script:adb shell input tap $c[0] ($c[1] + 40)
    Start-Sleep -Milliseconds 500
    & $script:adb shell input keyevent KEYCODE_MOVE_END
    1..12 | ForEach-Object { & $script:adb shell input keyevent KEYCODE_DEL }
    & $script:adb shell input text $value
    & $script:adb shell input keyevent KEYCODE_BACK  # close the keyboard
    return $true
}

function Reset-And-Connect {
    & $script:adb shell pm clear com.solana.mwallet | Out-Null
    & $script:adb shell pm clear app.vowed | Out-Null
    & $script:adb shell am start -n app.vowed/.MainActivity | Out-Null
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
    "home: " + (Has-Text "New challenge" 30)
}

function Sign-Reviewed($title, $timeoutSec = 90) {
    "review '$title': " + (Has-Text $title $timeoutSec)
    "sign: " + (Tap-Text "Sign with wallet" 20)
    "wallet approve: " + (Tap-Text "Approve" 120)
}
