# Builds ADRUU.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs: a JDK 17 and Android SDK build-tools + platform (see TOOLS below).
$ErrorActionPreference = 'Continue'   # tools print warnings on stderr; failures are caught via $LASTEXITCODE
$here  = Split-Path -Parent $MyInvocation.MyCommand.Path
$tools = "$env:LOCALAPPDATA\ADRUU-tools"
$jdk   = Get-ChildItem "$tools\jdk" -Directory | Select-Object -First 1 -ExpandProperty FullName
$sdk   = "$env:LOCALAPPDATA\Android"
$bt    = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$jar   = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name -Descending | Select-Object -First 1 | ForEach-Object { "$($_.FullName)\android.jar" }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$bt;$env:PATH"

$out = "$here\build"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

# Bundle the newest ADRUU Remote phone app so phones can update themselves from the TV.
$remote = Join-Path (Split-Path $here -Parent) "adruu-remote"
if (Test-Path "$remote\ADRUU-Remote.apk") {
    Copy-Item "$remote\ADRUU-Remote.apk" "$here\assets\remote.apk" -Force
    $vc = [regex]::Match([IO.File]::ReadAllText("$remote\AndroidManifest.xml"), 'versionCode="(\d+)"').Groups[1].Value
    [IO.File]::WriteAllText("$here\assets\remote-version.txt", $vc, (New-Object System.Text.UTF8Encoding($false)))
}

& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$here\AndroidManifest.xml" -A "$here\assets" --java "$out\gen" "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 link failed" }

$src = @(Get-ChildItem "$here\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
& "$jdk\bin\javac.exe" -encoding UTF-8 --release 8 -classpath $jar -d "$out\classes" @src
if ($LASTEXITCODE) { throw "javac failed" }

& "$jdk\bin\jar.exe" cf "$out\classes.jar" -C "$out\classes" .
& "$bt\d8.bat" --release --min-api 26 --lib $jar --output "$out\dex" "$out\classes.jar"
if ($LASTEXITCODE) { throw "d8 failed" }

Copy-Item "$out\base.apk" "$out\unsigned.apk"
Push-Location "$out\dex"; & "$bt\aapt.exe" add "$out\unsigned.apk" classes.dex | Out-Null; Pop-Location
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"

# Signing key for ADRUU (created once, kept next to the project, never shared).
$keys = "$env:LOCALAPPDATA\ADRUU-tools\keys\adruu-tv"   # outside the repo: never commit signing keys
$ks = "$keys\adruu.jks"; $pw = "$keys\password.txt"
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force $keys | Out-Null
    $pass = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Set-Content -Path $pw -Value $pass -NoNewline -Encoding ascii
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass $pass -keypass $pass -alias adruu `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ADRUU" | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass "file:$pw" --out "$here\ADRUU.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }
# Android can't open APK entries with Windows backslashes in their path.
$bad = & "$jdk\bin\jar.exe" tf "$here\ADRUU.apk" | Where-Object { $_ -match '\\' }
if ($bad) { throw "APK has backslash paths (put assets in the assets root): $($bad -join ', ')" }
"Built: $here\ADRUU.apk ({0:N0} KB)" -f ((Get-Item "$here\ADRUU.apk").Length / 1KB)
