# ADRUU TV — feature spec

Target: the current TCL TV (no streaming stick for now). Delivered as a custom
home-screen app ("ADRUU Launcher") plus a phone remote page, installed over ADB.
Status of each item: ✅ buildable · ⚠️ depends on TV hardware / needs checking · ❌ not doing.

## Build status — ADRUU 1.8 + ADRUU Remote 1.1 (2026-11-15)
Installed on the TV and tested (API 31/31, new features 21/21, logic 25/25, 0 warnings, 0 crashes after fixes):
default home screen (Google TV home + setup fallback disabled), TCL ads/analytics/logs disabled, scroll animation,
12-hour weather, photo wallpapers + upload + theme from photo, custom startup video (≤100 MB), bug-report bot
with self-repair, family reports (Malayalam), phone touchpad/air mouse/scroll/power/apps/uninstall, phone app
self-update from the TV, sound set for Sony HT-S500RF on HDMI ARC.
Left on the TV from testing (tidy up next session, when nobody is watching): a test Ferrari photo in "My photos"
with the "From my photo" theme selected; switch back with `/api/config {"theme":"midnight","wallpaper":"all"}`
and `/api/wallpaper/delete`. The TV was put on standby at the end (family prayer time).
Not done: video wallpapers (too heavy for 2 GB RAM), turning the TV ON from the phone (standby cuts Wi-Fi),
AI assistant, Atmos (the HT-S500RF doesn't list Atmos), live cricket bar, button remapping, weekly backup.

## Build status — ADRUU 1.1 (installed on the TV 2026-11-15, tested)
Working on the TV: landing intro + sound · tablet home with favourites first · clock/date/weather ·
5 themes + festival themes · quick buttons (Mode, Mouse, Lock now, Phone remote) · 6-digit PIN lock
(on/off, after standby, 5-try lockout, wrong-PIN alerts) · minimal settings · modes (Auto/Normal/Movie/
Music/Game → do-not-disturb) · mouse pointer over any app · CPU/RAM overlay · screen time · guest mode ·
phone page (unlock/lock, modes, mouse, send link, open app, install APK, theme, screen time, alerts) ·
Claude live control API (see CLAUDE.md).
Not yet the default home screen (Google TV's home still opens with the Home button).

### Bug test 2026-11-15 (ADRUU 1.2)
- Logic tests 25/25 pass (festival dates, modes, themes); 0 compiler warnings; 0 crashes/ANRs in TV logs.
- API tests 25/28 while ADRUU was running; failures were the test expecting old behaviour (fixed)
  and the server being killed mid-test (below).
- Fixed: unknown mode/theme accepted; empty APK upload started a broken install; Auto-mode check and
  control-file write ran on the screen thread (stutter risk); weather kept the old city for 30 min;
  service didn't restart after updates; TCL blocked ADRUU from starting in the background
  (granted TCL appop APP_AUTO_START).
- OPEN: TCL's memory guard (TGuard) kills ADRUU every time it runs in the background while another app
  (e.g. YouTube) is playing; Android's restart back-off then grows (44 s → 3 min). While that happens the
  phone page, unlock-from-phone, Claude control, Auto mode and the mouse pointer are unavailable.
  Likely fix: make ADRUU the default home screen (TGuard spares the current launcher) — not yet verified.

Still to build: AI assistant · unlock from anywhere (Tailscale) · Atmos passthrough tweak ·
ad blocking (private DNS) · TCL bloat removal / speed booster / night cleanup · Wi-Fi test ·
live cricket score bar · USB photo slideshow · remote button remapping · weekly backup · video calls.

## 0. Your TV (read over ADB, 2026-11-15)
| | |
|---|---|
| Model | TCL "Smart TV Pro" (G03_4K_GB, BeyondTV4) |
| System | Google TV, Android 11, signed in to Google |
| Chip | Realtek RTD288O, 4 cores (Cortex-A55, running 32-bit) |
| RAM | 2 GB — only ~450 MB free, ~420 MB pushed to swap |
| Storage | 10 GB, 3.3 GB free |
| Screen | 4K panel, menus drawn at 1080p, **60 Hz only** |
| HDR | **Dolby Vision, HDR10, HDR10+, HLG** ✅ |
| Audio out | HDMI ARC with TCL eARC service; Sony soundbar linked over HDMI-CEC ✅ |
| Built-in | Play Store ✅, Google Assistant ✅, Chromecast ✅, Bluetooth ✅, USB ✅, TV mic manager present |
| Home screen | Google TV launcher (the heaviest single app: ~180 MB) |

**Why it lags:** too little free RAM. Google TV's home screen plus ~15 TCL background services
(user center, message box, app promos, analytics "bi", logging, partner customizer, etc.)
take most of the 2 GB, so the TV keeps swapping. System load average was ~27 on 4 cores.

**What this changes:** Play Store, Google Home, casting and voice already work — no workarounds needed.
The work is: free RAM (disable TCL bloat, replace the heavy Google TV home screen with ADRUU),
tune animations, set audio to pass Atmos to the soundbar, then build the ADRUU features.

**Apps already installed:** Netflix, Prime Video, JioHotstar, SonyLIV, YouTube, YouTube Music, FanCode,
MX Player, Spotify, JioTV, ZEE5, Sun NXT, Manorama Max, Pocket FM, Downloader, Send Files to TV,
plus NetMirror, CineHD and "com.community.newroom" (flagged — see chat).

## 1. TV lock
- ✅ 6-digit PIN lock screen, shown at startup and after waking from standby/screensaver
- ✅ On/off switch in settings (PIN needed to turn it off)
- ✅ Wrong-PIN alert to your phone; 5 wrong tries → 1-minute wait
- ✅ Unlock from your phone from anywhere in the world (internet relay, see §10)
- ❌ App locks, lock hours (not wanted)

## 2. Look and home screen
- ✅ Tablet-style home: big app tiles + widgets
- ✅ Switchable themes
- ✅ Festival themes by date: Onam, Vishu, Christmas, Diwali, Eid, New Year (on/off)
- ✅ Widgets: clock, weather for your location
- ✅ Ambient screensaver (photos / art when idle)
- ✅ Startup animation (~6 s, any key skips): a jet lands on the runway and stops, the door opens,
  stairs come down, a pilot in uniform walks down toward you and waves, "ADRUU" appears below him.
  Sound: jet roar, touchdown thump, engines spooling down, cabin chime.
  (Plays when the launcher starts; the TCL boot logo before it can't be removed without root)
- ✅ Menu click sounds (on/off)
- ✅ Minimal settings screen, plain words
- ❌ Live wallpapers, family profiles, auto theme by time of day (not wanted)

## 3. Modes (one button on home screen)
- Movie mode: picture/sound tuned for films, notifications muted
- Music mode: visualizer screen, music sound profile
- Normal mode: everyday
- Auto mode: switches based on which app is open (Netflix → Movie, Spotify/YouTube Music → Music)
- ⚠️ How much of the TV's own picture/sound settings an app can change depends on TCL's system — to check

## 4. Sound and picture
- ⚠️ Dolby Atmos to the Sony soundbar over HDMI ARC/eARC: set TV audio output to passthrough/Auto.
  Works if the TV's HDMI ARC passes Dolby Digital Plus (Atmos in Netflix/Prime streams); TrueHD Atmos needs eARC
- ⚠️ Dolby Vision / HDR: only if the TV panel supports it — check model
- ✅ Equalizer and bass presets (if the TV allows audio effects)
- ✅ Bluetooth headphones and speakers
- ✅ Night / eye comfort
- Note: the soundbar's own sound modes do the "cinema" tuning; the TV's job is to pass the full signal untouched
- Note: 4K and high frame rate only if the panel is 4K / high-refresh — the app can't add what the screen doesn't have

## 5. Smoothness and speed
- ✅ Disable TCL bloat/ads/tracking apps (reversible, list shown before doing it)
- ✅ Tuned animation speeds, lightweight launcher, background app limit
- ✅ Speed booster button (clears background apps)
- ✅ Night auto-cleanup (on/off)
- ✅ Live CPU / RAM / temperature overlay (on/off)
- ✅ Ad and tracker blocking via private DNS (blocks most ads; not YouTube's own ads)
- ✅ Wi-Fi speed test + tips (5 GHz band or LAN cable gives the steadiest stream)
- ❌ Overclocking (risks damaging the TV)

## 6. Control
- ✅ Phone remote (web page, no app install)
- ✅ Type from phone keyboard
- ✅ Voice search via the phone's microphone
- ✅ Mouse pointer mode (toggle on remote, cursor moved with arrow keys or phone touchpad)
- ✅ Game mode switch (for a future PS5/controller: low-latency picture, notifications off)

## 7. Apps
- ⚠️ Play Store — to check on this TV
- Install: YouTube (official), Netflix, Prime Video, JioHotstar, SonyLIV, FanCode, Chrome (or Brave if Chrome won't run on TV)
- ✅ USB video player (VLC) for pen drives
- ✅ Install APKs sent from phone (upload page on phone remote) + "unknown sources" enabled
- ⚠️ Ad-free YouTube (SmartTube): use signed out, not with personal Google account (see notes)
- ❌ Telegram (not wanted)
- ❌ NetMirror (pirated streams — not installing)

## 8. Watching
- ✅ Continue-watching row on home screen
- ✅ Subtitles on/off setting (VLC auto-downloads subtitles for USB videos)
- ✅ Screen mirroring from phone (receiver app — check support)
- ⚠️ Cast from phone: only works on Google-certified TVs
- ✅ Photo slideshow: Google Photos + USB
- ✅ Screen time tracker
- ✅ Guest mode (hides personal apps and settings)
- ⚠️ Video calls with USB webcam: depends on TV USB camera support
- ❌ Universal search, one watchlist, picture-in-picture, morning news, prayer times, reminders, sleep timer, kids mode

## 9. Sports
- ✅ Live score bar, match reminders (cricket / football)

## 10. ADRUU AI assistant
- ✅ Talk through the phone remote page (phone mic) — always works
- ⚠️ Talk through the TV's built-in mic — if TCL lets apps use it
- ⚠️ Talk through the remote's mic button — if the mic button can be pointed at ADRUU instead of TCL's voice app
- ✅ English and Malayalam
- ✅ Controls the TV: "open Netflix", "movie mode", "lock the TV", "volume up", "play Vishu songs on YouTube"
- ✅ Answers questions on screen (cricket scores, weather, general questions)
- ⚠️ "Hey ADRUU" wake word (always listening) — optional, off by default, since it uses CPU
- Needs: internet + an AI service key (paid per use, small cost)

## 11. Extra features (added)
- ✅ Send to TV: share a YouTube/web link from the phone and it opens on the TV (works without casting)
- ✅ Cricket button: one tile that opens whichever app has the live match
- ✅ TV on/off and soundbar volume from phone (HDMI-CEC; power-on depends on TV's network standby)
- ✅ Remote button remapping (e.g. TCL's unused buttons → ADRUU AI, mouse mode, movie mode)
- ✅ Wi-Fi QR code on screen for guests
- ✅ Watch-time stats (screen time per app, per week)
- ✅ Weekly automatic backup of the whole ADRUU setup
- ✅ Do-not-disturb during movies (no pop-ups)

## 12. Remote access for Claude and you
- Private network (Tailscale) on the TV and this PC so ADB works from anywhere, never exposed to the open internet
- Same channel powers "unlock from phone anywhere"
- Claude can connect whenever you open a session on this PC — not a 24/7 background service
