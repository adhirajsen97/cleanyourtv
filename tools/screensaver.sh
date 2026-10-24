#!/usr/bin/env bash
# Choose the TV's screensaver and how long the TV waits before starting it, and check that it starts.
# Before the first change it prints an undo block and appends it to ~/.cleanyourtv/screensaver-<host>.log,
# because tools/recovery.py does not record these settings. Nothing is uninstalled or disabled, and
# the screensaver's package is enabled only when it is disabled (a debloat list may have done that).
# Every read and write names Android user 0, the profile the debloat lists change: without --user,
# `settings` and `query-services` act on whichever profile is in front (the TV may have a second one).
#
# Usage: TV_HOST=192.168.1.50 tools/screensaver.sh                     # show the current setup
#        TV_HOST=192.168.1.50 tools/screensaver.sh google [--after 5]  # Google TV's Ambient mode
#        TV_HOST=192.168.1.50 tools/screensaver.sh clock [--after 5]   # the launcher's Clock
#        TV_HOST=192.168.1.50 tools/screensaver.sh <pkg/.Service> [--after N]
#        add --test to start it right away, take a screenshot and put the idle time back
set -euo pipefail
set -f   # values read from the TV are split into words below; none of them is a file pattern

GOOGLE_DREAM=com.google.android.apps.tv.dreamx/com.google.android.apps.tv.dreamx.service.Backdrop
CLOCK_DREAM=com.gathin.tvhome/.ClockDream
SHOT_ON_TV=/sdcard/cyt-screensaver.png
TEST_TIMEOUT=15000
SECURE_KEYS="screensaver_components screensaver_enabled screensaver_default_component screensaver_activate_on_sleep screensaver_activate_on_dock sleep_timeout attentive_timeout"

# The component goes into commands run by the TV's shell, so only plain Java names get through (the
# idea of HOME_RE in tools/recovery.py, minus `$`, which the TV's shell would expand).
PKG_RE='[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+'
CLASS_RE='(\.[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*|[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+)'
COMPONENT_RE="^$PKG_RE/$CLASS_RE\$"
PKG_ONLY_RE="^$PKG_RE\$"
# Previous values only go into the undo block when they look like what Android stores there.
LIST_RE="^$PKG_RE/$CLASS_RE(,$PKG_RE/$CLASS_RE)*\$"
INT_RE='^-?[0-9]+$'
# Components as the TV prints them, in query-services and dumpsys output.
TOKEN_RE="$PKG_RE/[A-Za-z0-9_.\$]+"
# Android 12 says mCurrentDreamName=ComponentInfo{...}; newer builds print mCurrentDream=DreamRecord{...}.
DREAM_LINE_RE='(mCurrentDream|mCurrentDreamName|mCurrentDreamComponent)=|DreamRecord\{'

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
usage() {
  cat <<'EOF'
usage: tools/screensaver.sh                           show the screensaver setup
       tools/screensaver.sh google [--after N]        Google TV's Ambient mode after N minutes idle
       tools/screensaver.sh clock [--after N]         the launcher's Clock screensaver
       tools/screensaver.sh <pkg/.Service> [--after N]
       tools/screensaver.sh <one of the above> --test start it now, screenshot it, put the delay back
N is whole minutes from 1 to 120 (default 5). Set TV_HOST to the TV's IP address.
EOF
}

TARGET=""; AFTER=5; AFTER_GIVEN=0; TEST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --after) [ $# -ge 2 ] || { usage >&2; die "--after needs a number of minutes"; }
             AFTER=$2; AFTER_GIVEN=1; shift 2 ;;
    --after=*) AFTER=${1#--after=}; AFTER_GIVEN=1; shift ;;
    --test) TEST=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) usage >&2; die "unknown option: $1" ;;
    *) [ -z "$TARGET" ] || die "one screensaver at a time, got '$TARGET' and '$1'"
       TARGET=$1; shift ;;
  esac
done
if [ -z "$TARGET" ] && { [ "$AFTER_GIVEN" = 1 ] || [ "$TEST" = 1 ]; }; then
  die "name the screensaver first, e.g. tools/screensaver.sh clock --after 5 --test"
fi
# Whole minutes only, checked before any arithmetic: bash would read 08 as octal and 1e3 as a name.
[[ $AFTER =~ ^[0-9]{1,3}$ ]] && [ "$((10#$AFTER))" -ge 1 ] && [ "$((10#$AFTER))" -le 120 ] \
  || die "--after takes whole minutes from 1 to 120, not '$AFTER'"
AFTER=$((10#$AFTER))
MS=$((AFTER * 60000))
case "$TARGET" in
  "") COMPONENT="" ;;
  google) COMPONENT=$GOOGLE_DREAM ;;
  clock) COMPONENT=$CLOCK_DREAM ;;
  *) [[ $TARGET =~ $COMPONENT_RE ]] \
       || die "not a screensaver component: '$TARGET' (expected google, clock, or package/.Class)"
     COMPONENT=$TARGET ;;
esac

# Resolve the screenshot path against the caller's directory before moving into the repo. Git Bash's
# pwd says /c/Users/..., which the Windows Python cannot open; pwd -W gives C:/Users/...
CALLER_DIR="$(pwd -W 2>/dev/null || pwd)"
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
TAG=$(printf '%s' "$TV_HOST" | tr -c 'A-Za-z0-9._-' '_')
LOG="${HOME:-$PWD}/.cleanyourtv/screensaver-$TAG.log"
SHOT="${SCREENSAVER_SHOT:-screensaver-test-$TAG.png}"
case "$SHOT" in /*|[A-Za-z]:[/\\]*) ;; *) SHOT="$CALLER_DIR/$SHOT" ;; esac
if [ "$TEST" = 1 ] && [ -d "$SHOT" ]; then
  die "SCREENSAVER_SHOT is a folder ($SHOT); give it a file name, e.g. $SHOT/screensaver.png"
fi

# tv.py exits 0 for whatever the TV's shell answers, so every caller reads the output instead.
tv() { "$PY" -I tools/tv.py "$@" || true; }

full_component() {   # pkg/.Class -> pkg/pkg.Class, the form ComponentName prints in full
  local pkg=${1%%/*} cls=${1#*/}
  case "$cls" in .*) cls="$pkg$cls" ;; esac
  printf '%s/%s\n' "$pkg" "$cls"
}
has_line() {   # needle, newline-separated haystack
  case $'\n'"$2"$'\n' in *$'\n'"$1"$'\n'*) return 0 ;; esac
  return 1
}

read_settings() {   # sets cur_<key>; a key the TV does not have reads as null, like `settings get`
  local out line k ns="" secure=0 system=0
  # Two `settings list` in one call instead of eight `settings get`: each of those starts a process on
  # the TV. The marker line tells the namespaces apart and shows the second command ran.
  out=$(tv 'echo "-- secure --"; settings list --user 0 secure; echo "-- system --"; settings list --user 0 system')
  for k in $SECURE_KEYS screen_off_timeout; do printf -v "cur_$k" '%s' null; done
  while IFS= read -r line; do
    line=${line%$'\r'}
    case "$line" in
      "-- secure --") ns=secure; continue ;;
      "-- system --") ns=system; continue ;;
      *=*) ;;
      *) continue ;;
    esac
    k=${line%%=*}
    case "$ns" in
      secure) secure=$((secure + 1))
              case " $SECURE_KEYS " in *" $k "*) printf -v "cur_$k" '%s' "${line#*=}" ;; esac ;;
      system) system=$((system + 1))
              [ "$k" != screen_off_timeout ] || cur_screen_off_timeout=${line#*=} ;;
    esac
  done <<< "$out"
  [ "$secure" -gt 0 ] && [ "$system" -gt 0 ] || die "could not read the settings from $TV_HOST. Check TV_HOST
and the \"Allow USB debugging?\" prompt on the TV (tools/tv.py --probe)."
}
read_disabled() {
  DISABLED=$(tv 'pm list packages -d --user 0' | tr -d '\r' | sed -n 's/^package://p')
}
read_dreams() {   # the screensavers the TV can start; a disabled package's services are not listed
  local c
  DREAMS=$(tv 'cmd package query-services --user 0 --brief -a android.service.dreams.DreamService' \
    | tr -d '\r' | grep -oE "$TOKEN_RE" || true)
  DREAMS_FULL=$(for c in $DREAMS; do full_component "$c"; done)
}
read_all() { read_settings; read_disabled; read_dreams; }

pkg_state() {
  local c
  if has_line "$1" "$DISABLED"; then echo "disabled"; return; fi
  for c in $DREAMS_FULL; do
    case "$c" in "$1"/*) echo "enabled"; return ;; esac
  done
  echo "no screensaver found in it (not installed?)"
}
ms_text() {
  if [[ $1 =~ ^[0-9]{1,10}$ ]] && [ "$1" -gt 0 ]; then
    if [ $(($1 % 60000)) -eq 0 ]; then echo "$1 ($(($1 / 60000)) min)"
    elif [ $(($1 % 1000)) -eq 0 ]; then echo "$1 ($(($1 / 1000)) s)"
    else echo "$1"; fi
  else echo "${1:-(empty)}"; fi
}

show() {
  local k name pkg pkgs="" c
  echo "Screensaver on $TV_HOST (Android user 0):"
  for k in screensaver_components screensaver_enabled screensaver_default_component \
           screensaver_activate_on_sleep screensaver_activate_on_dock; do
    name=cur_$k
    printf '  %-31s %s\n' "$k" "${!name:-(empty)}"
  done
  printf '  %-31s %s\n' "screen_off_timeout (system)" "$(ms_text "$cur_screen_off_timeout")  idle time before it starts"
  printf '  %-31s %s\n' sleep_timeout "$(ms_text "$cur_sleep_timeout")  left alone"
  printf '  %-31s %s\n' attentive_timeout "$(ms_text "$cur_attentive_timeout")  left alone"
  echo "Screensavers the TV can start now:"
  if [ -n "$DREAMS" ]; then printf '  %s\n' $DREAMS; else echo "  none"; fi
  echo "Their packages:"
  for c in ${cur_screensaver_components//,/ } $cur_screensaver_default_component $GOOGLE_DREAM $CLOCK_DREAM $COMPONENT; do
    pkg=${c%%/*}
    [[ $pkg =~ $PKG_ONLY_RE ]] || continue
    case " $pkgs " in *" $pkg "*) continue ;; esac
    pkgs="$pkgs $pkg"
    printf '  %-40s %s\n' "$pkg" "$(pkg_state "$pkg")"
  done
}

undo_line() { printf "TV_HOST=%s %s -I tools/tv.py '%s'\n" "$TV_HOST" "$PY" "$1"; }
undo_setting() {   # namespace key previous-value pattern
  if [ -z "$3" ] || [ "$3" = null ]; then undo_line "settings delete --user 0 $1 $2"
  elif [[ $3 =~ $4 ]]; then undo_line "settings put --user 0 $1 $2 $3"
  else printf '# %s was "%s", which is not pasted into a command here; set it back by hand\n' \
         "$2" "$(printf '%s' "$3" | tr -cd '[:print:]' | tr -d "'")"
  fi
}
undo_block() {
  echo "# $(date '+%Y-%m-%d %H:%M:%S %z') before: tools/screensaver.sh $TARGET --after $AFTER$([ "$TEST" = 0 ] || echo ' --test')"
  echo "# Undo, from the repo root:"
  undo_setting secure screensaver_components "$cur_screensaver_components" "$LIST_RE"
  undo_setting secure screensaver_enabled "$cur_screensaver_enabled" "$INT_RE"
  undo_setting system screen_off_timeout "$cur_screen_off_timeout" "$INT_RE"
  if [ "$SET_ON_SLEEP" = 1 ]; then
    undo_setting secure screensaver_activate_on_sleep "$cur_screensaver_activate_on_sleep" "$INT_RE"
  fi
  if [ "$NEED_ENABLE" = 1 ]; then undo_line "pm disable-user --user 0 $PKG"; fi
}

put() {   # one tv.py call per write, so the TV sees them in this order
  local out
  out=$(tv "settings put --user 0 $1 $2 $3" | tr -d '\r')
  echo "  settings put --user 0 $1 $2 $3"
  [ -z "$out" ] || printf '    %s\n' "$out"
}

# --test lowers the idle time to a few seconds. Whatever stops the script after that, the requested
# idle time goes back: on success, on an error, and on Ctrl-C.
RESTORE_PENDING=0
restore_delay() {
  local now
  [ "$RESTORE_PENDING" = 1 ] || return 0
  RESTORE_PENDING=0
  tv "settings put --user 0 system screen_off_timeout $MS" >/dev/null
  now=$(tv 'settings get --user 0 system screen_off_timeout' | tr -d '\r\n')
  if [ "$now" = "$MS" ]; then echo "Idle time is back to $AFTER min."; return 0; fi
  echo "ERROR: the idle time is still ${now:-unknown}, not $MS. Put it back with:" >&2
  undo_line "settings put --user 0 system screen_off_timeout $MS" >&2
  return 1
}
on_exit() {
  local status=$?
  restore_delay || { [ "$status" -ne 0 ] || status=1; }
  exit "$status"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_test() {
  local dump matched c now dreaming=0
  echo
  echo "Testing: the idle time goes to $((TEST_TIMEOUT / 1000)) s for about half a minute."
  # Wake the TV first: a screensaver that is already running (the previous one) stops, and the idle
  # time counts from now.
  tv 'input keyevent KEYCODE_WAKEUP' >/dev/null
  RESTORE_PENDING=1
  put system screen_off_timeout "$TEST_TIMEOUT"
  # Read back like every other write: a lowering that did not take would cost the wait and then be
  # reported as a screensaver that did not start. The EXIT trap puts the requested idle time back.
  now=$(tv 'settings get --user 0 system screen_off_timeout' | tr -d '\r\n')
  [ "$now" = "$TEST_TIMEOUT" ] \
    || die "could not lower the idle time for the test (the TV says ${now:-nothing}), so nothing was tested."
  # The wait runs on the TV, in the same call as the check.
  dump=$(tv 'sleep 25; dumpsys dreams' | tr -d '\r')
  matched=$(printf '%s\n' "$dump" | grep -E "$DREAM_LINE_RE" | sed 's/^[[:space:]]*//' || true)
  for c in $(printf '%s\n' "$matched" | grep -oE "$TOKEN_RE" || true); do
    [ "$(full_component "$c")" != "$WANT_FULL" ] || dreaming=1
  done
  if [ -n "$matched" ]; then printf '%s\n' "$matched" | sed 's/^/  dumpsys dreams: /'
  else echo "  dumpsys dreams names no current screensaver"; fi
  tv "screencap -p $SHOT_ON_TV" >/dev/null
  # A file that cannot be replaced (a read-only folder) costs the screenshot, not the rest of the test
  # (the result, the wake-up, cleaning up the TV), and an older picture there is not passed off as this one.
  if rm -f "$SHOT" 2>/dev/null; then
    tv --pull "$SHOT_ON_TV" "$SHOT" >/dev/null 2>&1
    if [ -s "$SHOT" ]; then echo "  screenshot: $SHOT"
    else echo "WARNING: no screenshot came back from the TV ($SHOT_ON_TV)." >&2; fi
  else
    echo "WARNING: could not replace $SHOT, so the screenshot was not saved." >&2
  fi
  tv "rm -f $SHOT_ON_TV" >/dev/null
  tv 'input keyevent KEYCODE_WAKEUP' >/dev/null
  restore_delay || exit 1
  if [ "$dreaming" = 0 ]; then
    die "$COMPONENT was not running $((TEST_TIMEOUT / 1000)) s after the last key press. The TV has to
be on, with nothing on screen that keeps it awake (a playing video). See the lines above and the screenshot."
  fi
  echo "OK: $COMPONENT started."
  if [ "$WANT_FULL" = "$GOOGLE_DREAM" ]; then
    echo "Look at the screenshot: \"Unable to load content\" means Ambient mode cannot load its photos on"
    echo "this TV (docs/TROUBLESHOOTING.md); tools/screensaver.sh clock --after $AFTER goes back to the Clock."
  fi
}

read_all
if [ -z "$COMPONENT" ]; then show; exit 0; fi

PKG=${COMPONENT%%/*}
WANT_FULL=$(full_component "$COMPONENT")
NEED_ENABLE=0
if has_line "$PKG" "$DISABLED"; then NEED_ENABLE=1
elif ! has_line "$WANT_FULL" "$DREAMS_FULL"; then
  show
  die "$COMPONENT is not a screensaver on this TV (see the list above); nothing was changed."
fi
SET_ON_SLEEP=0
case "$cur_screensaver_activate_on_sleep" in ""|null) SET_ON_SLEEP=1 ;; esac

show
echo
BLOCK=$(undo_block)
printf '%s\n' "$BLOCK"
# The undo goes to disk before the first change, and the TV is left alone if it cannot: the terminal
# that shows the block may be gone by the time someone needs it.
{ mkdir -p "$(dirname "$LOG")" && printf '%s\n\n' "$BLOCK" >> "$LOG"; } 2>/dev/null \
  || die "could not write the undo block to $LOG; nothing was changed."
echo "(saved in $LOG)"
echo

if [ "$NEED_ENABLE" = 1 ]; then
  # On its own: on this kind of TV a pm command chained with others can silently stop the rest.
  echo "Enabling $PKG, which is disabled:"
  tv "pm enable --user 0 $PKG" | tr -d '\r' | sed 's/^/  /'
  read_disabled
  read_dreams
  has_line "$PKG" "$DISABLED" && die "$PKG is still disabled; nothing else was changed."
  has_line "$WANT_FULL" "$DREAMS_FULL" \
    || die "$PKG is enabled now, but the TV does not list $COMPONENT as a screensaver. Nothing else
was changed; the undo block above disables $PKG again."
fi

echo "Writing:"
put secure screensaver_components "$COMPONENT"
put secure screensaver_enabled 1
put system screen_off_timeout "$MS"
if [ "$SET_ON_SLEEP" = 1 ]; then put secure screensaver_activate_on_sleep 1; fi
echo

read_all
FAILED=""
[ "$(full_component "$cur_screensaver_components")" = "$WANT_FULL" ] \
  || FAILED="$FAILED"$'\n'"  screensaver_components: wanted $COMPONENT, the TV says ${cur_screensaver_components:-(empty)}"
[ "$cur_screensaver_enabled" = 1 ] \
  || FAILED="$FAILED"$'\n'"  screensaver_enabled: wanted 1, the TV says ${cur_screensaver_enabled:-(empty)}"
[ "$cur_screen_off_timeout" = "$MS" ] \
  || FAILED="$FAILED"$'\n'"  screen_off_timeout: wanted $MS, the TV says ${cur_screen_off_timeout:-(empty)}"
if [ "$SET_ON_SLEEP" = 1 ] && [ "$cur_screensaver_activate_on_sleep" != 1 ]; then
  FAILED="$FAILED"$'\n'"  screensaver_activate_on_sleep: wanted 1, the TV says ${cur_screensaver_activate_on_sleep:-(empty)}"
fi
has_line "$PKG" "$DISABLED" && FAILED="$FAILED"$'\n'"  $PKG is disabled"
has_line "$WANT_FULL" "$DREAMS_FULL" || FAILED="$FAILED"$'\n'"  $COMPONENT is not listed as a screensaver"
show
if [ -n "$FAILED" ]; then
  echo "ERROR: not everything took:$FAILED" >&2
  echo "The undo block above puts back the values from before." >&2
  exit 1
fi
if [ "$cur_screensaver_activate_on_sleep" = 0 ]; then
  echo "WARNING: screensaver_activate_on_sleep is 0, so the TV switches the screen off instead of starting" >&2
  echo "the screensaver. This script leaves it alone; turn it on with: $(undo_line 'settings put --user 0 secure screensaver_activate_on_sleep 1')" >&2
fi
echo "Done: $COMPONENT after $AFTER min idle."
if [ "$TEST" = 1 ]; then run_test; fi
