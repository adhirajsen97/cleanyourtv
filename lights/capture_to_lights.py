#!/usr/bin/env python3
"""Turn what the Learn screens recorded (capture.json, capture-cove.json) into the app's packet list (lights.json).

Usage: python3 -I lights/capture_to_lights.py lights/capture.json [more captures...] lights/app/src/main/assets/lights.json

Later capture files override the steps of earlier ones, so a short re-run of a few steps (Learn
light remote with `-e steps extras`) is merged over the full capture, and the cove remote's capture
goes in beside the light remote's.

The light remote sends every command twice, as manufacturer data (format A, readable) and as service
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
one after another.

The cove remote (the "TC" touch remote of the ambient strip round the ceiling) is not decoded. Its
steps are filed as heard: a packet of a step is the remote's when the quiet step heard neither the
same bytes, nor a packet of the same shape (the records' AD types with the maker and subtype bytes
after them, which a phone's rotating-address packets keep), nor the same sender. Packets within
PRESS_GAP_S of each other make one press, sent one
after another, and each press is a variant. The app can send service data and manufacturer data
records only; a packet in any other record is named on stderr and left out. The slide step is
reported, not filed: it is there to show how the ring encodes the colour.
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

COVE = "cove"
for step_id, command in {
    "cove-on": "on", "cove-off": "off", "cove-brighter": "brighter", "cove-dimmer": "dimmer",
    "cove-cool": "cool", "cove-neutral": "neutral", "cove-warm": "warm",
    "cove-yellow-dot": "yellow-dot", "cove-white-dot": "white-dot",
}.items():
    STEP_TO_COMMAND[step_id] = (COVE, command)
SLIDE = "cove-slide"

LIGHTS = [
    ("all", "All rows"),
    ("row-1", "Row 1, over the sofas"),
    ("row-2", "Row 2, fan and pendant"),
    ("row-3", "Row 3, towards the dining table"),
    ("row-4", "Row 4"),
    (COVE, "Cove, the ambient strip round the ceiling"),
    ("extras", "Other remote buttons"),
]
# Lights with a remote of their own: All rows does not switch them, so the screen does not mark them with it.
NOT_IN_ALL = {COVE}
PRESS, RELEASE = "44", "5C"
PAIR_BURST_MS = 400   # each packet of a multi-packet press; the light remote itself sends them for 0.2 to 0.6 s
PRESS_GAP_S = 0.5     # packets closer than this belong to one press of an undecoded remote
FLAGS, TX_POWER, SERVICE_DATA, MANUFACTURER_DATA = 0x01, 0x0A, 0x16, 0xFF
MAX_PAYLOAD = 27      # a legacy advertisement: 31 bytes less the record header


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
    """The light remote's format B packets of a step grouped by press: [[hex], ...] or [[press hex, release hex], ...]."""
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


def packet(hexdata):
    return {"service_data": "08F0", "data": hexdata}


def parsed_records(heard):
    """[(AD type, the bytes after it)] of a captured packet, from its "records" text."""
    out = []
    for r in heard["records"].split(" | "):
        b = [int(x, 16) for x in r.split()]
        if len(b) >= 2:
            out.append((b[1], b[2:]))
    return out


def as_packet(heard):
    """A lights.json packet for a captured one; None, with the reason on stderr, when the app cannot send it."""
    recs = parsed_records(heard)
    flags = any(t == FLAGS for t, _ in recs)
    usable = [(t, body) for t, body in recs if t in (SERVICE_DATA, MANUFACTURER_DATA) and len(body) > 2]
    if not usable:
        types = ", ".join(f"{t:02X}" for t, _ in recs) or "none"
        print(f"  {heard['raw']}: no service data or manufacturer data record (AD types {types}); "
              "the app cannot send it", file=sys.stderr)
        return None
    kind, body = usable[0]
    if len(body) - 2 > MAX_PAYLOAD:
        print(f"  {heard['raw']}: {len(body) - 2} bytes of payload, more than a legacy advertisement holds; left out",
              file=sys.stderr)
        return None
    if len(usable) > 1 or len(recs) > len(usable) + (1 if flags else 0):
        print(f"  {heard['raw']}: more records than the app sends; only the first service or manufacturer data "
              "record is kept", file=sys.stderr)
    key = "service_data" if kind == SERVICE_DATA else "manufacturer_data"
    p = {key: f"{body[1]:02X}{body[0]:02X}", "data": "".join(f"{x:02X}" for x in body[2:])}
    if not flags:
        p["connectable"] = False
    return p


def shape(heard):
    """AdData.shape of a captured packet: each record's AD type and the first three bytes after it, Flags and TX power left out."""
    pieces = []
    for r in heard["records"].split(" | "):
        b = [int(x, 16) for x in r.split()]
        if len(b) < 2 or b[0] == 0 or len(b) < b[0] + 1:
            break
        if b[1] not in (FLAGS, TX_POWER):
            pieces.append("".join(f"{x:02X}" for x in b[1:5]))
    return "|".join(pieces)


def baseline(steps):
    """What the quiet steps heard: the raw packets, their shapes and the senders; none of them is a remote."""
    raws, shapes, senders = set(), set(), set()
    for step in steps.values():
        if step.get("quiet"):
            for p in step["packets"]:
                raws.add(p["raw"])
                shapes.add(shape(p))
                if p.get("address"):
                    senders.add(p["address"])
    return raws, shapes, senders


def heard_presses(step, quiet):
    """An undecoded remote's packets in a step, grouped by press: ([[lights.json packets], ...], senders)."""
    raws, shapes, senders = quiet
    own = sorted((p for p in step["packets"]
                  if p["raw"] not in raws and shape(p) not in shapes and p.get("address") not in senders),
                 key=lambda p: p["first"])
    groups = []
    for p in own:
        if groups and p["first"] - groups[-1][-1]["first"] <= PRESS_GAP_S:
            groups[-1].append(p)
        else:
            groups.append([p])
    out = []
    for g in groups:
        packets = [q for q in (as_packet(p) for p in g) if q]
        if packets:
            out.append(packets)
    return out, sorted({p.get("address") or "(no address)" for p in own})


def main(capture_paths, out_path):
    steps = {}
    for path in capture_paths:
        for step in json.load(open(path, encoding="utf-8"))["steps"]:
            if step["packets"]:
                steps[step["step"]] = step
    quiet = baseline(steps)
    if any(STEP_TO_COMMAND.get(s, ("",))[0] == COVE for s in steps) and not any(quiet):
        print("no quiet step in the captures: every packet heard during the cove steps counts as the remote's",
              file=sys.stderr)
    commands = {light: {} for light, _ in LIGHTS}
    for step in steps.values():
        target = STEP_TO_COMMAND.get(step["step"])
        if target is None:
            if step["step"] == SLIDE:
                groups, senders = heard_presses(step, quiet)
                print(f"{SLIDE}: {sum(len(g) for g in groups)} packets from {', '.join(senders) or 'nobody'}, "
                      "not filed (they show how the ring encodes the colour)", file=sys.stderr)
            continue
        light, command = target
        if light == COVE:
            groups, senders = heard_presses(step, quiet)
            print(f"{step['step']}: {len(groups)} presses heard, from {', '.join(senders) or 'nobody'}", file=sys.stderr)
        else:
            groups = [[packet(hexdata) for hexdata in g] for g in presses(step)]
        variants = []
        for g in groups:
            if len(g) > 1:
                g = [dict(p, burst_ms=PAIR_BURST_MS) for p in g]
            variants.append(g)
        if not variants:
            print(f"{step['step']}: nothing heard, {light}/{command} left empty", file=sys.stderr)
            continue
        commands[light][command] = variants[0] if len(variants) == 1 else {"variants": variants}
    lights = []
    for light, name in LIGHTS:
        entry = {"id": light, "name": name}
        if light in NOT_IN_ALL:
            entry["in_all"] = False
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
