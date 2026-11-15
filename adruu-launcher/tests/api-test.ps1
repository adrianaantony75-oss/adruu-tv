# ADRUU API tests. Safe while someone is watching: nothing here opens apps, locks the TV or moves the pointer.
# Settings changed during the test are put back at the end.
param([string]$Tv = "192.168.10.8")
$adb = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe"
$cfg = (& $adb -s "${Tv}:5555" shell cat /sdcard/Android/data/com.adruu.tv/files/control.json) | ConvertFrom-Json
$t = $cfg.token; $base = "http://${Tv}:8686"
$pass = 0; $fail = 0
function Check($name, $ok, $detail = "") {
    if ($ok) { $script:pass++; "PASS  $name" } else { $script:fail++; "FAIL  $name  $detail" }
}
function Code($url, $method = "GET", $body = $null) {
    try {
        $r = Invoke-WebRequest $url -Method $method -Body $body -UseBasicParsing -TimeoutSec 20
        return @{ code = [int]$r.StatusCode; body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object IO.StreamReader($resp.GetResponseStream()); $b = $sr.ReadToEnd()
            return @{ code = [int]$resp.StatusCode; body = $b }
        }
        return @{ code = -1; body = $_.Exception.Message }
    }
}

# --- security ---
Check "no token -> 401" ((Code "$base/api/status").code -eq 401)
Check "wrong token -> 401" ((Code "$base/api/status?t=000000000000").code -eq 401)
Check "token in header works" ((Invoke-WebRequest "$base/api/status" -Headers @{ "X-ADRUU-Token" = $t } -UseBasicParsing).StatusCode -eq 200)
Check "phone page needs token" ((Code "$base/").code -eq 401)

# --- status ---
$before = Invoke-RestMethod "$base/api/status?t=$t"
Check "status ok" ($before.ok -eq $true)
Check "status has 5 themes" ($before.themes.Count -eq 5)
Check "status has events list" ($null -ne $before.events)
$sw = [Diagnostics.Stopwatch]::StartNew(); Invoke-RestMethod "$base/api/status?t=$t" | Out-Null; $sw.Stop()
Check "status answers in < 1.5 s" ($sw.ElapsedMilliseconds -lt 1500) "$($sw.ElapsedMilliseconds) ms"

# --- live config round trip ---
$r = Invoke-RestMethod -Method Post "$base/api/config?t=$t" -Body '{"clicks":false,"perf":false,"theme":"ocean"}'
$s = Invoke-RestMethod "$base/api/status?t=$t"
Check "config changes clicks" ($s.clicks -eq $false)
Check "config changes theme" ($s.theme -eq "ocean")
Check "config reports changed keys" (($r.changed -join ",") -eq "theme,clicks,perf")
Check "empty config body ok" ((Code "$base/api/config?t=$t" "POST" "").code -eq 200)
Check "bad JSON rejected, server survives" (((Code "$base/api/config?t=$t" "POST" "{not json").code -ne 200) -and ((Code "$base/api/status?t=$t").code -eq 200))
Check "config lockOn:true ignored (can't lock remotely without PIN)" (((Invoke-RestMethod -Method Post "$base/api/config?t=$t" -Body '{"lockOn":true}').changed.Count) -eq 0)

# --- input validation ---
Check "unknown theme rejected" ((Code "$base/api/theme?id=nope&t=$t").code -eq 400)
Check "unknown theme in config rejected" ((Code "$base/api/config?t=$t" "POST" '{"theme":"nope"}').code -eq 400)
Check "wallpaper kind accepted" ((Code "$base/api/config?t=$t" "POST" '{"wallpaper":"plane"}').code -eq 200)
Check "unknown wallpaper rejected" ((Code "$base/api/config?t=$t" "POST" '{"wallpaper":"boats"}').code -eq 400)
Check "unknown mode rejected" ((Code "$base/api/mode?id=banana&t=$t").code -eq 400)
Check "open non-web link rejected" ((Code "$base/api/open?url=file:///sdcard&t=$t").code -eq 400)
Check "open without url rejected" ((Code "$base/api/open?t=$t").code -eq 400)
Check "launch unknown app rejected" ((Code "$base/api/launch?pkg=no.such.app&t=$t").code -eq 400)
Check "lock without PIN set rejected" ((Code "$base/api/lock?t=$t").code -eq 400)
Check "empty APK upload rejected" ((Code "$base/api/apk?t=$t" "POST" "").code -eq 400)
Check "unknown path -> 400" ((Code "$base/api/zzz?t=$t").code -eq 400)
Check "oversized config body rejected, server survives" (((Code "$base/api/config?t=$t" "POST" ("x" * 2000000)).code -ne 200) -and ((Code "$base/api/status?t=$t").code -eq 200))

# --- read-only data ---
$apps = Invoke-RestMethod "$base/api/apps?t=$t"
Check "apps list has Netflix" ($apps.apps.pkg -contains "com.netflix.ninja")
Check "apps list hides ADRUU itself" (-not ($apps.apps.pkg -contains "com.adruu.tv"))
$st = Invoke-RestMethod "$base/api/screentime?t=$t"
Check "screen time returns data" ($st.ok -and $st.totalMinutes -ge 0)

# --- concurrency: 12 parallel status calls ---
$jobs = 1..12 | ForEach-Object { Start-Job -ScriptBlock { param($u) try { (Invoke-WebRequest $u -UseBasicParsing -TimeoutSec 30).StatusCode } catch { -1 } } -ArgumentList "$base/api/status?t=$t" }
$codes = $jobs | Wait-Job | Receive-Job; $jobs | Remove-Job
Check "12 parallel requests all succeed" ((@($codes | Where-Object { $_ -eq 200 })).Count -eq 12) ($codes -join ",")

# --- restore settings ---
Invoke-RestMethod -Method Post "$base/api/config?t=$t" -Body (@{ clicks = $before.clicks; perf = $before.perf; theme = $before.theme; mode = $before.mode; wallpaper = $before.wallpaper } | ConvertTo-Json) | Out-Null
$s = Invoke-RestMethod "$base/api/status?t=$t"
Check "settings restored" ($s.theme -eq $before.theme -and $s.clicks -eq $before.clicks -and $s.mode -eq $before.mode -and $s.wallpaper -eq $before.wallpaper)

""; "{0} passed, {1} failed" -f $pass, $fail
