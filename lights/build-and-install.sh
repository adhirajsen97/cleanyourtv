#!/usr/bin/env bash
# Build the Home Lights APK and install it on the TV. Nothing else on the TV changes: no Home
# handover, nothing disabled. Usage: TV_HOST=192.168.1.26 lights/build-and-install.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.26 $0}"
# macOS: java_home or Homebrew; Linux: the java on PATH.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || { [ -d /opt/homebrew/opt/openjdk@17 ] && echo /opt/homebrew/opt/openjdk@17; } || java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')}"
# Android Studio's default SDK location on macOS, then on Linux, then Homebrew's command-line tools.
export ANDROID_HOME="${ANDROID_HOME:-$(for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -d "$d" ] && echo "$d" && break; done)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain
TOOLS="$(cd ../tools && pwd)"   # absolute, so the venv's Python doesn't warn about "../" in its prefix
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=tv.homelights
APK=app/build/outputs/apk/release/app-release.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/homelights.apk
OUT=$("$PY" -I ../tools/tv.py 'pm install -r /data/local/tmp/homelights.apk; rm -f /data/local/tmp/homelights.apk')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *) echo "ERROR: pm install failed (see above)." >&2; exit 1 ;;
esac
# Android 12 and newer: advertising is a runtime permission. Grant it here so the first press works
# without a dialog. On older Android the grant fails, which is fine: there the permission comes with
# the install and the app never asks.
printf '%s\n' \
  "pm grant $PKG android.permission.BLUETOOTH_ADVERTISE 2>/dev/null && echo 'BLUETOOTH_ADVERTISE granted' || echo 'BLUETOOTH_ADVERTISE: not a runtime permission on this Android, nothing to grant'" \
  "pm grant $PKG android.permission.BLUETOOTH_CONNECT 2>/dev/null && echo 'BLUETOOTH_CONNECT granted' || echo 'BLUETOOTH_CONNECT: not a runtime permission on this Android, nothing to grant'" \
  | "$PY" -I ../tools/tv.py --batch
START_OUT=$("$PY" -I ../tools/tv.py "am start -W -n $PKG/.MainActivity")
echo "$START_OUT"
if ! printf '%s' "$START_OUT" | grep -qF 'Status: ok' || printf '%s' "$START_OUT" | grep -qi '^Error'; then
  echo "ERROR: Home Lights did not start (see above)." >&2
  exit 1
fi
echo "Installed. Home Lights is on screen: read the status line, then press On or Off on the remote."
echo "Scripted check:  TV_HOST=$TV_HOST tools/venv/bin/python -I tools/tv.py 'am start -n $PKG/.MainActivity -e light drawing-room -e state on'"
echo "Log:             TV_HOST=$TV_HOST tools/venv/bin/python -I tools/tv.py 'logcat -d -s HomeLights'"
