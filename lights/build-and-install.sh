#!/usr/bin/env bash
# Build the Home Lights APK and install it on the TV. Nothing else on the TV changes: no Home
# handover, nothing disabled. Usage: TV_HOST=192.168.1.26 lights/build-and-install.sh
#
# Needs a JDK 17 to 22 (Gradle 8.9 runs on nothing newer; Android Studio's bundled runtime or
# `brew install openjdk@17` both do) and the Android SDK (Android Studio installs it). The script
# looks in the usual places and says which it uses, or what is missing; JAVA_HOME and ANDROID_HOME
# override the search.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.26 $0}"
# Major version of the java under a JDK home ("17.0.12" -> 17, "1.8.0_392" -> 8), nothing if it does not run.
java_major() {
  [ -x "$1/bin/java" ] || return 1
  "$1/bin/java" -version 2>&1 | awk -F'"' '/ version "/ { split($2, v, "."); print (v[1] == "1") ? v[2] : v[1]; exit }'
}
# A JDK Gradle 8.9 can run on (17 to 22), from the first of these that is one: JAVA_HOME, macOS's
# registry of installed JDKs, Homebrew's openjdk on Apple Silicon and Intel, Android Studio's bundled
# runtime, the java on PATH. Each candidate is checked, so a JDK 25 that happens to come first is
# skipped rather than used (Gradle would stop with "Unsupported class file major version 69").
find_jdk() {
  local home major
  for home in \
    "${JAVA_HOME:-}" \
    "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
    "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
    /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 \
    /opt/homebrew/opt/openjdk@21 /usr/local/opt/openjdk@21 \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$(java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}' || true)"
  do
    [ -n "$home" ] || continue
    major=$(java_major "$home") || continue
    [ -n "$major" ] || continue
    if [ "$major" -ge 17 ] && [ "$major" -le 22 ]; then
      echo "$home"
      return 0
    fi
    echo "skipping JDK $major at $home (Gradle 8.9 runs on 17 to 22)" >&2
  done
  return 1
}
if ! JAVA_HOME=$(find_jdk); then
  echo "ERROR: no JDK 17-22 found (Gradle 8.9 cannot run on a newer Java). One of:" >&2
  echo "  brew install openjdk@17" >&2
  echo "  install Android Studio (its bundled runtime is used automatically)" >&2
  echo "  JAVA_HOME=/path/to/jdk-17 $0" >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
echo "Using JDK $(java_major "$JAVA_HOME") at $JAVA_HOME"

# Android Studio's default SDK location on macOS, then on Linux, then Homebrew's command-line tools.
export ANDROID_HOME="${ANDROID_HOME:-$(for d in ~/Library/Android/sdk ~/Android/Sdk /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do [ -d "$d" ] && echo "$d" && break; done)}"
if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
  echo "ERROR: no Android SDK found${ANDROID_HOME:+ at $ANDROID_HOME}. One of:" >&2
  echo "  install Android Studio (the SDK comes with it, under ~/Library/Android/sdk on a Mac)" >&2
  echo "  brew install --cask android-commandlinetools, then: sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\"" >&2
  echo "  ANDROID_HOME=/path/to/sdk $0" >&2
  exit 1
fi
echo "Using Android SDK at $ANDROID_HOME"
# local.properties pins the SDK for Gradle. Written once, and again when it points at a folder that is gone.
SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | head -n 1 || true)
if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR" ]; then echo "sdk.dir=$ANDROID_HOME" > local.properties; fi
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
