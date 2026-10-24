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
"$PY" -I "$TOOLS/tv.py" --pull "/sdcard/Android/data/tv.homelights/files/$NAME" "$OUT"
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
