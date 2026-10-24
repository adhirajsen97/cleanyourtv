#!/usr/bin/env bash
# Fetch what a Learn screen recorded on the TV to the computer.
# Usage: TV_HOST=192.168.1.26 lights/pull-capture.sh [--cove] [output file]
#   Learn light remote writes capture.json on the TV, fetched to lights/capture.json by default;
#   --cove fetches the cove remote's capture-cove.json, to lights/capture-cove.json by default.
set -euo pipefail
NAME=capture.json
if [ "${1:-}" = --cove ]; then NAME=capture-cove.json; shift; fi
OUT="${1:-}"
case "$OUT" in ""|/*) ;; *) OUT="$PWD/$OUT" ;; esac
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.26 $0}"
OUT="${OUT:-$PWD/$NAME}"
TOOLS="$(cd ../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
REMOTE=/sdcard/Android/data/tv.homelights/files/$NAME
if [ "$NAME" = capture-cove.json ]; then SCREEN="Learn cove remote"; else SCREEN="Learn light remote"; fi
# A missing file is the usual mistake (the screen not run, or Back before its first step), so say so plainly.
EXISTS=$("$PY" -I "$TOOLS/tv.py" "[ -f $REMOTE ] && echo yes || echo no" | tr -d '\r' | tail -n 1)
if [ "$EXISTS" != yes ]; then
  echo "No $NAME on the TV. $SCREEN writes it after its first step (Next step), so open $SCREEN on the TV," >&2
  echo "go through the steps, press Finish, then run this again. Files there now:" >&2
  "$PY" -I "$TOOLS/tv.py" "ls /sdcard/Android/data/tv.homelights/files/ 2>/dev/null || echo '(none)'" >&2
  exit 1
fi
"$PY" -I "$TOOLS/tv.py" --pull "$REMOTE" "$OUT"
"$PY" -I - "$OUT" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
steps = d["steps"]
print(f"{len(steps)} steps, {sum(len(s['packets']) for s in steps)} distinct packets, started {d['started']} on {d['device']}")
for s in steps:
    print(f"  {s['step']:<16} {len(s['packets']):>3} packets  {s['copies']:>4} copies  {s['ignored']:>5} other"
          + ("  (quiet: the room's own packets, left out of the later steps)" if s.get("quiet") else ""))
PY
echo "Saved to $OUT. Commit it as lights/$NAME, or paste it into the chat."
