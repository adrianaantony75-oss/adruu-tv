# ADRUU TV — how Claude controls the TV

The owner's TCL Google TV runs **ADRUU**, a custom home screen built in `adruu-launcher/`.
Feature list and status: `ADRUU-TV-SPEC.md`. When the owner asks for a change, use the
channels below — no rebuild is needed for settings; code changes need a rebuild + install.

## The TV
- TCL "Smart TV Pro" (G03_4K_GB), Google TV / Android 11, 2 GB RAM, Realtek RTD288O
- Home Wi-Fi address: `192.168.10.8` (ADB on port 5555, ADRUU API on port 8686)
- ADB: `%LOCALAPPDATA%\Android\platform-tools\adb.exe` → `adb connect 192.168.10.8:5555`

## 1. Live settings (no rebuild) — ADRUU API
Token: read it over ADB, never print it in chat or put it in files outside the TV:
```
adb -s 192.168.10.8:5555 shell cat /sdcard/Android/data/com.adruu.tv/files/control.json
```
Every call needs `?t=<token>` (or header `X-ADRUU-Token`). Without it the TV answers 401.

| Call | What it does |
|---|---|
| `GET /api/status` | lock state, theme, mode, guest, toggles, foreground app, recent alerts |
| `POST /api/config` + JSON body | change settings live (see keys below) |
| `GET /api/unlock` · `/api/lock` | unlock / lock the TV |
| `GET /api/mode?id=auto\|normal\|movie\|music\|game` | viewing mode |
| `GET /api/theme?id=midnight\|neon\|racing\|ocean\|minimal` | theme |
| `GET /api/mouse?on=1\|0` | mouse pointer |
| `GET /api/open?url=https://...` | open a link on the TV (YouTube links open in YouTube) |
| `GET /api/apps` · `/api/launch?pkg=...` · `/api/home` | list / open apps, go home |
| `GET /api/screentime` | minutes per app today |
| `POST /api/apk` (body = APK bytes) | install an APK (TV asks to confirm) |

| `GET /api/key?k=back\|home\|playpause\|next\|prev\|volup\|voldown\|mute` | remote buttons (volume reaches the soundbar via CEC) |
| `GET /api/hello` (no token) | discovery for the phone app |
| `POST /api/pair/start`, `POST /api/pair?code=NNNN` (no token) | phone pairing: TV shows a 4-digit code for 2 min, 5 tries |

`/api/config` keys: `theme, wallpaper (all|plane|car|bike|off), festival, clicks, intro, perf, subtitles,
city, mode, guest, lockOn (false only), hidden[], favourites[], personal[]` (lists are package names).

## Phone app — ADRUU Remote (`adruu-remote/`)
Tiny Android app (20 KB): scans the phone's Wi-Fi for `/api/hello`, pairs with the TV's code, stores the
token, then shows the TV's control page in a WebView. Sharing a link to it opens the link on the TV.
Build: `powershell -File adruu-remote\build.ps1` → `adruu-remote\ADRUU-Remote.apk` (own signing key in
`adruu-remote\keystore\`). Install on phones by sending the APK (allow "install unknown apps").

## Wallpapers
Photos are `adruu-launcher/assets/wall_<plane|car|bike>-NN.jpg`, 1920x1080 JPEG (~300 KB). Keep them in the
assets ROOT (sub-folders get Windows backslashes and Android can't open them; build.ps1 checks this).
To add the owner's own picture: centre-crop to 16:9, resize to 1920x1080, save as e.g. `wall_car-20.jpg`,
add a line to `wall_credits.txt`, bump the version, rebuild, install.

## Sound (Sony HT-S500RF on HDMI 3 / ARC)
Set 2026-11-15: `encoded_surround_output=0` (auto), HDMI-CEC on, system audio + CEC volume on.
ARC is established and the bar is the active speaker. The HT-S500RF decodes Dolby Digital 5.1
(Sony doesn't list Dolby Atmos for this model), so Netflix's Atmos flag stays off.

## Home screen setup (done 2026-11-15, undo with `adruu-launcher\tests\undo-tv-changes.ps1`)
- Disabled (pm disable-user): Google TV home `launcherx`, setup fallback `setupwraith`
  (both needed: Google TV ignores the preferred-home setting), and TCL `messagebox, overseasappshow,
  bi, logkit, usercenter, tv.tclhome_passive`.
- TCL appop `APP_AUTO_START` = allow for com.adruu.tv (TCL Safety Guard blocks background starts otherwise).

## Tests
- `adruu-launcher\tests\api-test.ps1` — safe while someone watches (no on-screen effects).
- `adruu-launcher\tests\tv-test.ps1` — takes over the TV, restarts it; only when nobody is watching.
- Logic tests: `tests\com\adruu\tv\LogicTest.java` (see bug-test notes in ADRUU-TV-SPEC.md).
Example (PowerShell):
```
Invoke-RestMethod -Method Post "http://192.168.10.8:8686/api/config?t=$t" -Body '{"theme":"neon","favourites":["com.netflix.ninja","in.startv.hotstar"]}'
```
The phone page is the same server: `http://192.168.10.8:8686/?t=<token>`.

## 2. Code changes — rebuild and install
```
powershell -File adruu-launcher\build.ps1
adb -s 192.168.10.8:5555 install -r adruu-launcher\ADRUU.apk
```
Build tools: JDK 17 in `%LOCALAPPDATA%\ADRUU-tools\jdk`, Android SDK in `%LOCALAPPDATA%\Android`
(build-tools 34.0.0, platform android-30). No Gradle. Signing keys: `%LOCALAPPDATA%\ADRUU-tools\keys\adruu-tv`
and `...\keys\adruu-remote` (never commit; keep a backup: Android only accepts updates signed with the same key).
Build the phone app FIRST: the TV build bundles `adruu-remote\ADRUU-Remote.apk` so phones can update from the TV.
Grant once after install: `adb shell pm grant com.adruu.tv android.permission.WRITE_SECURE_SETTINGS`
(lets ADRUU switch its own accessibility service back on) and `appops set com.adruu.tv APP_AUTO_START allow` (TCL).

## Update workflow (how the family reports bugs)
1. Family members send reports from the phone app ("Report a problem", Malayalam or English, voice OK).
2. Read them: `GET /api/reports` (kinds: user, crash, freeze, health, repair).
3. Fix, bump versions, build, install when nobody is watching (updating the home app can restart the TV once).
4. Phones see "New phone app version" in the app and update from the TV. Publish a GitHub release too.

## Known limits
- Standby turns the TV's Wi-Fi off: /api/power?state=off works, "on" can't reach it (Wake-on-LAN tested: no).
Bump `versionCode`/`versionName` in `AndroidManifest.xml` and `BuildInfo.VERSION` for each release.

After an install, check these permissions are still on (re-run if not):
```
adb shell appops set com.adruu.tv SYSTEM_ALERT_WINDOW allow
adb shell appops set com.adruu.tv GET_USAGE_STATS allow
adb shell appops set com.adruu.tv REQUEST_INSTALL_PACKAGES allow
adb shell cmd notification allow_dnd com.adruu.tv
adb shell settings put secure enabled_accessibility_services com.adruu.tv/com.adruu.tv.MouseService
adb shell settings put secure accessibility_enabled 1
```

## Rules
- The TV is used by the family. Check `/api/status` → `foreground` before anything visible;
  don't inject key presses, relaunch apps or restart the TV while someone is watching.
- Disable TCL/Google packages only with the owner's OK, with `pm disable-user --user 0`
  (reversible: `pm enable <pkg>`), never `pm uninstall`. Keep a list of what was disabled in
  `ADRUU-TV-SPEC.md`.
- Never touch the owner's Google account, passwords or payment settings.
- Don't install piracy apps (e.g. NetMirror); the owner chose official apps only.
