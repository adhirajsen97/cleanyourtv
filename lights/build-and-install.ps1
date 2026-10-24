# Windows version of build-and-install.sh: build the Home Lights APK and install it on the TV.
# Uses Android Studio's bundled JDK and the default SDK location unless JAVA_HOME / ANDROID_HOME are set.
#
# Usage: lights\build-and-install.ps1 -TvHost 192.168.1.26
param(
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
    $env:JAVA_HOME = $jbr
}
# Gradle 8.9 runs on JDK 17 to 22; say so instead of letting Groovy fail on a newer Java.
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$javaOut = (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | ForEach-Object { "$_" }) -join "`n"
$ErrorActionPreference = $prev
$major = 0
if ($javaOut -match 'version "(\d+)(\.(\d+))?') { $major = [int]$Matches[1]; if ($major -eq 1 -and $Matches[3]) { $major = [int]$Matches[3] } }
if ($major -lt 17 -or $major -gt 22) {
    throw "JDK $major at $env:JAVA_HOME, but Gradle 8.9 runs on 17 to 22. Install Android Studio (its bundled runtime is found automatically) or set JAVA_HOME to a JDK 17."
}
Write-Host "Using JDK $major at $env:JAVA_HOME"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path $sdk)) { throw "no Android SDK at $sdk: install Android Studio (the SDK comes with it) or set ANDROID_HOME" }
Write-Host "Using Android SDK at $sdk"
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}

.\gradlew.bat assembleRelease -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$py = '..\tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\pip install -r tools\requirements.txt' }
$pkg = 'tv.homelights'
$apk = 'app\build\outputs\apk\release\app-release.apk'
& $py -I ..\tools\b64push.py $apk /data/local/tmp/homelights.apk
$out = & $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/homelights.apk; rm -f /data/local/tmp/homelights.apk'
Write-Host $out
if ($out -notmatch 'Success') { throw 'pm install failed (see above)' }

# Android 12 and newer: advertising is a runtime permission; grant it so the first press works without
# a dialog. On older Android the grant fails harmlessly, the permission came with the install.
@(
    "pm grant $pkg android.permission.BLUETOOTH_ADVERTISE 2>/dev/null && echo 'BLUETOOTH_ADVERTISE granted' || echo 'BLUETOOTH_ADVERTISE: not a runtime permission on this Android, nothing to grant'",
    "pm grant $pkg android.permission.BLUETOOTH_CONNECT 2>/dev/null && echo 'BLUETOOTH_CONNECT granted' || echo 'BLUETOOTH_CONNECT: not a runtime permission on this Android, nothing to grant'",
    "pm grant $pkg android.permission.BLUETOOTH_SCAN 2>/dev/null && echo 'BLUETOOTH_SCAN granted (Learn remote)' || echo 'BLUETOOTH_SCAN: not a runtime permission on this Android, nothing to grant'",
    "pm grant $pkg android.permission.ACCESS_FINE_LOCATION 2>/dev/null && echo 'ACCESS_FINE_LOCATION granted (Learn remote on Android 11 and older)' || echo 'ACCESS_FINE_LOCATION: not needed on this Android'"
) -join "`n" | & $py -I ..\tools\tv.py --batch

$start = & $py -I ..\tools\tv.py "am start -W -n $pkg/.MainActivity"
Write-Host $start
if ($start -notmatch 'Status: ok' -or $start -match '(?m)^Error') { throw 'Home Lights did not start (see above)' }
# A lights.json pushed with push-config.sh stays in force across reinstalls and hides the built-in list.
$override = (& $py -I ..\tools\tv.py "[ -f /sdcard/Android/data/$pkg/files/lights.json ] && echo yes || echo no" | Select-Object -Last 1)
if ("$override".Trim() -eq 'yes') {
    Write-Host 'NOTE: a lights.json pushed earlier is still on the TV and is used instead of the built-in one.'
    Write-Host "      Drop it with: lights\push-config.sh --reset (or tools\venv\Scripts\python.exe -I tools\tv.py 'rm /sdcard/Android/data/$pkg/files/lights.json; am force-stop $pkg')"
}
Write-Host 'Installed. Home Lights is on screen: read the status line, then press On or Off on the remote.'
Write-Host "Scripted check:  `$env:TV_HOST='$TvHost'; tools\venv\Scripts\python.exe -I tools\tv.py 'am start -n $pkg/.MainActivity -e light all -e command on'"
Write-Host "Log:             tools\venv\Scripts\python.exe -I tools\tv.py 'logcat -d -s HomeLights'"
