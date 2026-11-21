# ADRUU TV — setup and control API

## Tested hardware
- TCL Smart TV Pro (G03_4K_GB), Google TV / Android 11, 2 GB RAM
- Sony HT-S500RF soundbar on HDMI ARC (HDMI-CEC volume)

## Making ADRUU the home screen
Google TV ignores the "preferred home" setting, so the stock launchers are disabled (reversibly) with
`pm disable-user --user 0`:

- Google TV home `launcherx` and the setup fallback `setupwraith`
- Optional TCL extras: `messagebox`, `overseasappshow`, `bi`, `logkit`, `usercenter`, `tv.tclhome_passive`

Everything can be restored with [`adruu-launcher/tests/undo-tv-changes.ps1`](../adruu-launcher/tests/undo-tv-changes.ps1)
(`pm enable <package>`). Nothing is ever uninstalled.

### Permissions to grant after install
```
adb shell pm grant com.adruu.tv android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.adruu.tv APP_AUTO_START allow          # TCL only: lets the service start in the background
adb shell appops set com.adruu.tv SYSTEM_ALERT_WINDOW allow
adb shell appops set com.adruu.tv GET_USAGE_STATS allow
adb shell appops set com.adruu.tv REQUEST_INSTALL_PACKAGES allow
adb shell cmd notification allow_dnd com.adruu.tv
adb shell settings put secure enabled_accessibility_services com.adruu.tv/com.adruu.tv.MouseService
adb shell settings put secure accessibility_enabled 1
```
`WRITE_SECURE_SETTINGS` lets ADRUU switch its own accessibility service (mouse pointer) back on if the
system turns it off.

## Control API
The TV runs a small HTTP server on port **8686**. It serves the phone control page and a JSON API used
by the ADRUU Remote app and by scripts on a PC.

### Authentication
A random token is created on first start. Read it over ADB:
```
adb shell cat /sdcard/Android/data/com.adruu.tv/files/control.json
```
Send it as `?t=<token>` or as the `X-ADRUU-Token` header. Requests without it get `401`.

### Endpoints
| Call | What it does |
|---|---|
| `GET /api/status` | Lock state, theme, mode, guest mode, toggles, foreground app, recent alerts |
| `POST /api/config` (JSON body) | Change settings live (keys below) |
| `GET /api/lock` · `/api/unlock` | Lock / unlock instantly; without a PIN this is a "phone lock" |
| `POST /api/pin` `{"pin":"123456"}` or `{"remove":true}` | Set or remove the TV PIN |
| `GET /api/mode?id=auto\|normal\|movie\|music\|game` | Viewing mode |
| `GET /api/theme?id=midnight\|neon\|racing\|ocean\|minimal` | Theme |
| `GET /api/mouse?on=1\|0` | Mouse pointer on / off |
| `GET /api/mouse/move?dx&dy` · `/api/mouse/tap` · `/api/mouse/scroll?dir=` | Touchpad, air mouse and scroll |
| `GET /api/key?k=back\|home\|playpause\|next\|prev\|volup\|voldown\|mute` | Remote buttons (volume reaches the soundbar via CEC) |
| `GET /api/open?url=https://...` | Open a link on the TV (YouTube links open in YouTube) |
| `GET /api/apps` · `/api/launch?pkg=` · `/api/home` | List apps, open an app, go home |
| `GET /api/apps/uninstall?pkg=` · `/api/apps/store?pkg=\|q=` · `POST /api/order` | Uninstall (TV confirms), Play Store, tile order |
| `GET /api/screentime` | Minutes per app today |
| `GET /api/power?state=off` | Standby |
| `POST /api/apk` (APK bytes) | Install an APK (TV asks to confirm) |
| `POST /api/wallpaper/upload?album=&theme=1` · `GET /api/wallpaper/list` · `/api/wallpaper/delete?album&name` | Wallpaper albums and photo theme |
| `POST /api/intro/upload` (≤ 200 MB) · `GET /api/intro/clear` | Custom startup video / default animation |
| `POST /api/intruder/image` · `/api/intruder/audio` · `GET /api/intruder/clear?what=` · `/api/intruder/test` | Lock-screen "intruder" picture and voice |
| `GET /api/notify/test` | Test phone alert (ntfy) |
| `GET /api/reports` · `POST /api/reports/add` | Read / add bug reports (user, crash, freeze, health, repair) |
| `GET /api/hello` *(no token)* | Discovery for the phone app |
| `POST /api/pair/start` · `POST /api/pair?code=NNNN` *(no token)* | Pairing: the TV shows a 4-digit code for 2 minutes, 5 tries |

`/api/config` keys: `theme`, `wallpaper` (`all|plane|car|bike|off|album:<name>`), `festival`, `clicks`,
`intro`, `perf`, `subtitles`, `city`, `mode`, `guest`, `lockOn` (needs a PIN), `slideshow` (minutes),
`tiles` (`rounded|square`), `notify`, `intruderText`, `hidden[]`, `favourites[]`, `personal[]`
(lists are package names).

Example (PowerShell):
```powershell
Invoke-RestMethod -Method Post "http://<tv-ip>:8686/api/config?t=$token" `
  -Body '{"theme":"neon","favourites":["com.netflix.ninja","in.startv.hotstar"]}'
```

## Adding your own wallpapers
Wallpapers are `adruu-launcher/assets/wall_<plane|car|bike>-NN.jpg`, 1920×1080 JPEG (~300 KB), kept in the
assets root (Android cannot open asset paths with Windows backslashes; `build.ps1` checks this).
Add a credit line to `wall_credits.txt` for each new photo. Photos can also be uploaded from the phone app
without rebuilding.

## Releasing
Bump `versionCode` / `versionName` in `AndroidManifest.xml` and `BuildInfo.VERSION`, build the phone app
first (the TV app bundles it so phones can update from the TV), then build and install the TV app.
Keep a backup of the signing keys: Android only accepts updates signed with the same key.
