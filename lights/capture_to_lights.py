#!/usr/bin/env python3
"""Turn what Learn remote recorded (capture.json) into the app's packet list (lights.json).

Usage: python3 -I lights/capture_to_lights.py lights/capture.json [more captures...] lights/app/src/main/assets/lights.json

Later capture files override the steps of earlier ones, so a short re-run of a few steps (Learn
remote with `-e steps extras`) is merged over the full capture.

The remote sends every command twice, as manufacturer data (format A, readable) and as service
data under 0x08F0 (format B, the one the lights were seen to answer from the TV). Both go out
within a few hundredths of a second, so each format B packet takes its meaning from the format A
packet nearest in time. Format A, 27 bytes after the company id:

    byte 11  command   D0 on, 50 off, 44 adjustment press, 5C adjustment release,
                       1C moon, A0/20 RGB (toggles), 90/10 Aid (toggles)
    byte 13  group     9F all, 1F row 1, DF row 2, 5F row 3, BF row 4
    byte 17  counter   changes with every press; the lights ignore a repeat of the last one
    byte 18  which     FC brighter, 7C dimmer, BC K+, 3C K- (in the 44 packet)
    19 to 24           checksum over the rest

Every press is kept as a variant of its command; the app rotates through them so two presses in a
row never carry the same counter. An adjustment press is two packets, press then release, sent
one after the other.
"""
import json
import sys

STEP_TO_COMMAND = {
    "all-on": ("all", "on"), "all-off": ("all", "off"),
    "k-button": ("extras", "k"), "moon": ("extras", "moon"), "rgb": ("extras", "rgb"), "aid": ("extras", "aid"),
}
for row in (1, 2, 3, 4):
    STEP_TO_COMMAND[f"on-{row}"] = (f"row-{row}", "on")
    STEP_TO_COMMAND[f"off-{row}"] = (f"row-{row}", "off")
ADJUST = {"brighter": "brighter", "dimmer": "dimmer", "k-plus": "cooler", "k-minus": "warmer"}
# K+ and K- are filed by the Kelvin reading (K+ cooler). On the drawing-room lights, All rows does the
# opposite of the single rows even though the capture shows the same codes, so its two are swapped.
SWAP_K = {"all"}
for prefix, light in (("all", "all"), ("row1", "row-1"), ("row2", "row-2"), ("row3", "row-3")):
    for suffix, command in ADJUST.items():
        if light in SWAP_K and command in ("cooler", "warmer"):
            command = "warmer" if command == "cooler" else "cooler"
        STEP_TO_COMMAND[f"{prefix}-{suffix}"] = (light, command)

LIGHTS = [
    ("all", "All rows"),
    ("row-1", "Row 1, over the sofas"),
    ("row-2", "Row 2, fan and pendant"),
    ("row-3", "Row 3, towards the dining table"),
    ("row-4", "Row 4"),
    ("extras", "Other remote buttons"),
]
PRESS, RELEASE = "44", "5C"
PAIR_BURST_MS = 400   # each half of an adjustment press; the remote itself sends them for 0.2 to 0.6 s


def records(packet):
    return [r.split() for r in packet["records"].split(" | ")]


def split(step):
    a, b = [], []
    for p in step["packets"]:
        for r in records(p):
            if r[1] == "FF" and r[2:4] == ["56", "55"]:
                a.append((p["first"], r[4:]))
            elif r[1] == "16" and r[2:4] == ["F0", "08"]:
                b.append((p["first"], "".join(r[4:])))
    return sorted(a), sorted(b)


def presses(step):
    """The format B packets of a step grouped by press: [[hex], ...] or [[press hex, release hex], ...]."""
    a, b = split(step)
    if not a or not b:
        return []
    typed = []
    for t, hexdata in b:
        nearest = min(a, key=lambda x: abs(x[0] - t))
        if abs(nearest[0] - t) > 0.5:
            raise SystemExit(f"{step['step']}: a service-data packet at {t:.2f}s has no manufacturer twin")
        typed.append((nearest[1][11], hexdata))
    out = []
    i = 0
    while i < len(typed):
        kind, hexdata = typed[i]
        if kind == PRESS and i + 1 < len(typed) and typed[i + 1][0] == RELEASE:
            out.append([hexdata, typed[i + 1][1]])
            i += 2
        else:
            out.append([hexdata])
            i += 1
    return out


def packet(hexdata, burst_ms=None):
    p = {"service_data": "08F0", "data": hexdata}
    if burst_ms:
        p["burst_ms"] = burst_ms
    return p


def main(capture_paths, out_path):
    steps = {}
    for path in capture_paths:
        for step in json.load(open(path, encoding="utf-8"))["steps"]:
            if step["packets"]:
                steps[step["step"]] = step
    commands = {light: {} for light, _ in LIGHTS}
    for step in steps.values():
        target = STEP_TO_COMMAND.get(step["step"])
        if target is None:
            continue
        light, command = target
        variants = []
        for group in presses(step):
            if len(group) == 2:
                variants.append([packet(group[0], PAIR_BURST_MS), packet(group[1], PAIR_BURST_MS)])
            else:
                variants.append([packet(group[0])])
        if not variants:
            print(f"{step['step']}: nothing heard, {light}/{command} left empty", file=sys.stderr)
            continue
        commands[light][command] = variants[0] if len(variants) == 1 else {"variants": variants}
    lights = []
    for light, name in LIGHTS:
        entry = {"id": light, "name": name}
        if commands[light]:
            entry["commands"] = commands[light]
        lights.append(entry)
    result = {"burst_ms": 800, "connectable": True, "lights": lights}
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
        f.write("\n")
    for light, name in LIGHTS:
        summary = ", ".join(f"{c} x{len(v['variants']) if isinstance(v, dict) else 1}" for c, v in commands[light].items())
        print(f"{light:<8} {summary or '(no packets)'}")
    print(f"written {out_path}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    main(sys.argv[1:-1], sys.argv[-1])
