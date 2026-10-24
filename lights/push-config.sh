#!/usr/bin/env bash
# Put a lights.json on the TV and restart Home Lights so it reads it. The app uses the pushed file
# instead of its built-in list; see README.md for the format.
#
# Usage: TV_HOST=192.168.1.26 lights/push-config.sh path/to/lights.json
#        TV_HOST=192.168.1.26 lights/push-config.sh --reset      # back to the built-in list
set -euo pipefail
# Resolve the file against the caller's directory before moving into lights/.
ARG="${1:-}"
case "$ARG" in --reset|"") ;; /*) ;; *) ARG="$PWD/$ARG" ;; esac
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.26 $0 lights.json}"
TOOLS="$(cd ../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=tv.homelights
DIR=/sdcard/Android/data/$PKG/files
RESTART="am force-stop $PKG; am start -n $PKG/.MainActivity >/dev/null"
tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }

if [ "$ARG" = --reset ]; then
  tv "rm -f $DIR/lights.json; $RESTART; echo reset" | tail -n 1
  exit 0
fi
FILE="${ARG:?usage: push-config.sh <lights.json> | --reset}"
[ -f "$FILE" ] || { echo "no such file: $FILE" >&2; exit 1; }
"$PY" -I -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8-sig"))' "$FILE" 2>/dev/null \
  || { echo "$FILE is not valid JSON" >&2; exit 1; }
tv "mkdir -p $DIR" >/dev/null
tv --push "$FILE" "$DIR/lights.json"
tv "$RESTART; echo applied" | tail -n 1
echo "Home Lights now reads $(basename "$FILE") from the TV; its status line says so. Undo with: lights/push-config.sh --reset"
