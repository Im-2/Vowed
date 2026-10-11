# Watches the emulator and taps the rightmost button (Connect / Confirm / Approve) of every Phantom sheet that appears, until Vowed is back in front
# for good or the time is up. Test helper for docs/emulator-real-wallet-testing.md. Usage: powershell scripts/emu-phantom-approve.ps1 [-Seconds 120] [-Button right|left]
param([int]$Seconds = 120, [string]$Button = "right", [int]$MaxTaps = 3)
. "$PSScriptRoot\emu-lib.ps1"
$adb = "C:\Users\hp\Android\Sdk\platform-tools\adb.exe"
$x = if ($Button -eq "left") { 240 } else { 790 }
$end = (Get-Date).AddSeconds($Seconds)
$taps = 0
$seen = @()
while ((Get-Date) -lt $end -and $taps -lt $MaxTaps) {
    Start-Sleep -Milliseconds 1500
    $f = Focus
    if ($f -match "app.phantom") {
        $t = (Screen-Texts) -join "|"
        $kind = if ($t -match "Sign message") { "sign-message" } elseif ($t -match "Sign transaction|Confirm transaction|Approve transaction|Transaction request") { "transaction" } elseif ($t -match "Only connect") { "connect" } elseif ($t -match "Only confirm|Only approve") { "confirm" } else { $null }
        if ($kind) {
            $stamp = (Get-Date).ToString("HH:mm:ss")
            cmd /c "$adb exec-out screencap -p > $env:TEMP\phantom-$kind-$taps.png"
            "$stamp phantom screen: $kind -> tap $Button"
            & $adb shell input tap $x 2020
            $taps++
            Start-Sleep 3
        }
    }
}
"done: $taps taps; focus now: $(Focus)"
