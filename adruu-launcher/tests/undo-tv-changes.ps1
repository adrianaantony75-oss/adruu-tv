# Puts the TV back the way TCL shipped it: re-enables the TCL apps ADRUU turned off
# and makes Google TV's home screen the default again. ADRUU stays installed (uninstall it from Settings > Apps if wanted).
param([string]$Tv = "192.168.10.8")
$adb = "$env:LOCALAPPDATA\Android\platform-tools\adb.exe"; $d = "${Tv}:5555"
& $adb connect $d | Out-Null
# Turned off on 2026-11-15 with the owner's OK ("Ads, analytics, logs"):
$disabled = "com.tcl.messagebox", "com.tcl.overseasappshow", "com.tcl.bi", "com.tcl.logkit", "com.tcl.usercenter", "com.tcl.tv.tclhome_passive"
foreach ($p in $disabled) { & $adb -s $d shell pm enable $p }
# Google TV's own home screen, turned off so ADRUU opens with the Home button:
& $adb -s $d shell pm enable com.google.android.apps.tv.launcherx
& $adb -s $d shell pm enable com.google.android.tungsten.setupwraith
& $adb -s $d shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
"Done. Restart the TV to finish."
