# On-screen ADRUU test. TAKES OVER THE TV (restarts it, opens apps) - only run when nobody is watching.
param([string]$Tv = "192.168.10.8", [string]$Shots = "$env:TEMP\adruu-shots")
$adb = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe"; $d = "${Tv}:5555"
New-Item -ItemType Directory -Force $Shots | Out-Null
$pass = 0; $fail = 0
function Check($name, $ok, $detail = "") { if ($ok) { $script:pass++; "PASS  $name" } else { $script:fail++; "FAIL  $name  $detail" } }
function Sh($cmd) { ((& $adb -s $d shell $cmd 2>$null) -join "`n").Trim() }
function Focus { Sh "dumpsys window windows | grep -m1 -E 'mCurrentFocus|mFocusedApp'" }
function Shot($n) { Sh "screencap -p /sdcard/t.png" | Out-Null; & $adb -s $d pull /sdcard/t.png "$Shots\$n.png" 2>$null | Out-Null; Sh "rm /sdcard/t.png" | Out-Null }
function WaitBoot {
    $t0 = Get-Date; Start-Sleep -Seconds 35
    while (((Get-Date) - $t0).TotalSeconds -lt 400) {
        & $adb disconnect $d 2>$null | Out-Null
        if ("$(& $adb connect $d 2>&1)" -match 'connected to' -and (Sh "getprop sys.boot_completed") -eq '1') { return [int]((Get-Date) - $t0).TotalSeconds }
        Start-Sleep -Seconds 6
    }
    return -1
}

# 1. Cold start, like switching the TV on
& $adb -s $d reboot
$secs = WaitBoot
Check "TV boots and ADB reconnects" ($secs -gt 0) "$secs s"
Start-Sleep -Seconds 3
Check "ADRUU is in front after boot" ((Focus) -match 'com.adruu.tv/com.adruu.tv.MainActivity') (Focus)
Shot "1-boot-intro"
Start-Sleep -Seconds 12
Shot "2-boot-home"
Check "Home button target is ADRUU" ((Sh "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1") -eq 'com.adruu.tv/.MainActivity')
Check "Google TV home still off after boot" ((Sh "pm list packages -d | grep -c launcherx") -eq '1')
Check "setup fallback still off after boot" ((Sh "pm list packages -d | grep -c setupwraith") -eq '1')
Check "TCL bloat still off after boot" ([int](Sh "pm list packages -d | grep -c com.tcl") -ge 6)
Check "intro sound played (media AudioTrack)" ((Sh "logcat -d | grep -c 'client package name is : com.adruu.tv'") -ne '0')
Check "ADRUU server up after boot" ((Sh "netstat -tln | grep -c ':8686'") -ne '0')
# The TV is busy right after boot: give the mouse service up to 60 s to connect.
$bound = $false; foreach ($i in 1..12) { if ((Sh "dumpsys accessibility | grep -m1 'Bound services'") -match 'ADRUU') { $bound = $true; break }; Start-Sleep -Seconds 5 }
Check "mouse service bound after boot" $bound

# 2. Live settings / API (wait until ADRUU has written its control file and answers)
$t = $null; foreach ($i in 1..20) {
    try { $cfg = (Sh "cat /sdcard/Android/data/com.adruu.tv/files/control.json") | ConvertFrom-Json; $t = $cfg.token } catch { }
    if ($t) { try { if ((Invoke-WebRequest "http://${Tv}:8686/api/status?t=$t" -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200) { break } } catch { } }
    Start-Sleep -Seconds 3
}
Check "control file + API ready after boot" ([bool]$t)
$base = "http://${Tv}:8686"
Check "phone app discovery (/api/hello, no token)" ((Invoke-RestMethod "$base/api/hello").app -eq 'ADRUU')

# 3. Open an app, come back with Home
Invoke-RestMethod "$base/api/launch?pkg=com.netflix.ninja&t=$t" | Out-Null; Start-Sleep -Seconds 10
Check "app tile opens Netflix" ((Focus) -match 'netflix') (Focus)
Sh "input keyevent HOME" | Out-Null; Start-Sleep -Seconds 4
Check "Home returns to ADRUU" ((Focus) -match 'com.adruu.tv') (Focus)

# 4. Mouse pointer
Invoke-RestMethod "$base/api/mouse?on=1&t=$t" | Out-Null; Start-Sleep -Seconds 1
Check "mouse turns on" ((Invoke-RestMethod "$base/api/status?t=$t").mouse -eq $true)
Shot "3-mouse"
Sh "input keyevent BACK" | Out-Null; Start-Sleep -Seconds 1
Check "Back turns mouse off" ((Invoke-RestMethod "$base/api/status?t=$t").mouse -eq $false)

# 5. Modes -> do-not-disturb
Invoke-RestMethod "$base/api/mode?id=movie&t=$t" | Out-Null; Start-Sleep -Seconds 6
Check "Movie mode turns do-not-disturb on" ((Sh "settings get global zen_mode") -ne '0') (Sh "settings get global zen_mode")
Invoke-RestMethod "$base/api/mode?id=auto&t=$t" | Out-Null; Start-Sleep -Seconds 6
Check "Auto mode on home turns it off" ((Sh "settings get global zen_mode") -eq '0') (Sh "settings get global zen_mode")

# 5b. Phone remote keys and pairing
$v0 = Sh "cmd media_session volume --stream 3 --get 2>/dev/null | grep -o 'volume is [0-9]*'"
Check "remote Vol + accepted" ((Invoke-RestMethod "$base/api/key?k=volup&t=$t").ok)
Check "remote Vol - accepted" ((Invoke-RestMethod "$base/api/key?k=voldown&t=$t").ok)
Check "volume back where it was" ((Sh "cmd media_session volume --stream 3 --get 2>/dev/null | grep -o 'volume is [0-9]*'") -eq $v0) $v0
Check "remote Play/Pause accepted" ((Invoke-RestMethod "$base/api/key?k=playpause&t=$t").ok)
Check "remote Back works (accessibility)" ((Invoke-RestMethod "$base/api/key?k=back&t=$t").ok)
$code = 0; try { $code = (Invoke-WebRequest "$base/api/key?k=banana&t=$t" -UseBasicParsing).StatusCode } catch { $code = [int]$_.Exception.Response.StatusCode }
Check "unknown remote key rejected" ($code -eq 400)
Invoke-RestMethod -Method Post "$base/api/pair/start" | Out-Null; Start-Sleep -Seconds 2; Shot "5-pair-code"
$bad = 0; foreach ($i in 1..3) { try { Invoke-WebRequest -Method Post "$base/api/pair?code=99999" -UseBasicParsing | Out-Null } catch { if ([int]$_.Exception.Response.StatusCode -eq 401) { $bad++ } } }
Check "wrong pairing codes refused" ($bad -eq 3)
$leak = $false; try { $leak = ((Invoke-WebRequest -Method Post "$base/api/pair" -UseBasicParsing).Content -match 'token') } catch { }
Check "pairing without a code gives no token" (-not $leak)
Sh "input keyevent HOME" | Out-Null; Start-Sleep -Seconds 2

# 6. Wallpaper changes live
Invoke-RestMethod -Method Post "$base/api/config?t=$t" -Body '{"wallpaper":"car"}' | Out-Null; Start-Sleep -Seconds 3; Shot "4-wall-car"
Invoke-RestMethod -Method Post "$base/api/config?t=$t" -Body '{"wallpaper":"all"}' | Out-Null

# 7. Survives a long YouTube session (TCL memory guard)
Invoke-RestMethod "$base/api/launch?pkg=com.google.android.youtube.tv&t=$t" | Out-Null; Start-Sleep -Seconds 60
$kills = [int](Sh "logcat -d | grep -c 'Killing.*com.adruu.tv.*tguard'")
Check "ADRUU not killed while YouTube runs" ($kills -eq 0) "$kills kills"
$code = 0; try { $code = (Invoke-WebRequest "$base/api/status?t=$t" -UseBasicParsing -TimeoutSec 15).StatusCode } catch { }
Check "phone/Claude server answers during YouTube" ($code -eq 200)
Sh "input keyevent HOME" | Out-Null; Start-Sleep -Seconds 4
Check "Home from YouTube returns to ADRUU" ((Focus) -match 'com.adruu.tv') (Focus)

# 8. Health
Check "no ADRUU crashes" ((Sh "logcat -d -b crash | grep -c com.adruu") -eq '0')
Check "no ADRUU freezes (ANR)" ((Sh "logcat -d | grep -c 'ANR in com.adruu'") -eq '0')
"free RAM: " + (Sh "grep MemAvailable /proc/meminfo")
"ADRUU memory: " + (Sh "dumpsys meminfo com.adruu.tv | grep -m1 TOTAL")
""; "{0} passed, {1} failed   (screenshots in {2})" -f $pass, $fail, $Shots
