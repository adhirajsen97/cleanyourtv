#!/usr/bin/env python3
"""Drive a Sony BRAVIA (Google TV) Picture menu with D-pad keys, checking every step against a uiautomator dump.

    sonyui.py read [--json FILE]                    map every row and sub-option with its value (moves focus only)
    sonyui.py set [--label L] [--dry-run] [--force] 'Section/Row=Value' ['Section/Row/Sub-option=Value' ...]
    sonyui.py apply STEPS.json [--label L] [--dry-run] [--force]
    sonyui.py restore --label L [--dry-run] [--force]
    sonyui.py show-record
    sonyui.py dump FILE.xml | shot FILE.png

Sony keeps most picture settings out of reach of the shell: its settings providers are not exported or need a
signature permission, and the REST API (tools/bravia.py) only covers a dozen of them. The Picture panel itself is an
ordinary Android list, so this tool walks it the way a person does: Down to the row, Left/Right to change the value,
and a fresh UI dump after every key to make sure focus is on the row it means to change before the next key.
Center is only ever pressed on a collapsed section header, never on a row (that would run actions such as
"Reset picture settings"). If anything stops a run, the panel is closed only when it is still on screen.

A path names a row by its section header and its title as the TV shows them, e.g. 'Brightness/Peak luminance' or
'Motion/Motionflow/Smoothness' for a sub-option. 'Basic' is the section above the first header. The section can be
left out when the title is unique: 'Peak luminance=High'. Sliders take a number, Max or Min.

Sony keeps picture settings per picture mode, per kind of signal (SDR, HDR, Dolby Vision) and per kind of screen:
Home, video apps, and apps whose "<App> calibrated mode" is on each have their own memory. So every run has a label,
by default the app in front plus sdr or dv (e.g. com.netflix.ninja-dv), and a label is only ever used again over the
same app and the same kind of signal. Before the first key that changes a row, its value is recorded in
~/.cleanyourtv/sonyui-<TV_HOST>.json under the label and the picture mode it belongs to; the first value recorded
wins, so running a profile twice never records the profile as "before".

A Picture mode step comes first and is a precondition: if the mode cannot be selected (the row is greyed out
because an app's calibrated mode is on, or the mode is not in the list), nothing after it is changed.

`restore --label L`, with the same app in front and the same kind of content on screen, switches to each recorded
mode and puts its rows back, then returns to the mode that was active, then puts calibrated-mode switches back.
Calibrated itself cannot be chosen from the list; only its app's switch brings it back.

STEPS.json: {"panel": "picture", "set": {"Basic/Picture mode": "Custom", "Brightness/Peak luminance": "High"}}
Rows are changed in the order they appear on the TV, whatever the order in the file.
"""
import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PANELS = {
    # The only resolver of DISPLAY_SETTINGS on Sony's 2021+ Google TV sets: com.sony.dtv.configsettings/.PictureSettingActivity.
    "picture": "android.settings.DISPLAY_SETTINGS",
    "sound": "com.sony.dtv.base.soundsettings",
    "screen": "com.sony.dtv.base.screensettings",
}
SETTINGS_PACKAGE = "com.sony.dtv.configsettings"
FIRST_SECTION = "Basic"
PICTURE_MODE = "Picture mode"
# Sony's partner-app mode: switched on and off by "<App> calibrated mode" under Basic, never offered by the
# Picture mode list itself.
CALIBRATED = "Calibrated"
RECORD_VERSION = 1
# Values and titles go into `input`/`am` commands only through fixed key names, never as text, but a label
# ends up in a file and in messages: keep it plain, and never starting like an option.
LABEL_RE = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}")


class NotOffered(Exception):
    """A picker does not list the wanted value."""


class ModeNotReached(Exception):
    """The picture mode a pass needs could not be selected, so none of its rows were touched."""


DASHES = dict.fromkeys(map(ord, "\u2010\u2011\u2012\u2013\u2014\u2212"), "-")


def norm(text):
    """Compare titles and values the way a person reads them: case, dashes and spacing do not matter."""
    return " ".join((text or "").translate(DASHES).replace("\u00a0", " ").split()).casefold()


# ---- reading a dump ----------------------------------------------------------------------------------------

def _rid(node):
    return node.get("resource-id", "").rsplit("/", 1)[-1]


def _first_text(node, ids):
    for k in node.iter("node"):
        if _rid(k) in ids and k.get("text"):
            return k.get("text")
    return None


def _has(node, ids):
    return any(_rid(k) in ids for k in node.iter("node"))


def _control(container, title, own):
    """One value control: a picker (text + arrows) or a slider (currentValue + seekbar)."""
    slider = _has(container, ("seekBar", "expandedSeekBar", "normalSeekBar"))
    value = _first_text(container, ("expandCurrentValue", "currentValue") if slider else ("text",))
    if own:
        left, right = _has(container, ("expandedArrowLeft",)), _has(container, ("expandedArrowRight",))
        marked = left or right or _has(container, ("defaultIndicator",))
    else:
        left, right = _has(container, ("arrowLeft",)), _has(container, ("arrowRight",))
        marked = left or right or _has(container, ("defaultIndicator",))
    kind = "slider" if slider else ("picker" if value is not None else "action")
    return {"title": title, "value": value, "kind": kind, "left": left, "right": right, "marked": marked}


def _subs(row):
    """Sub-options shown under an expanded row: a title TextView followed by its value container."""
    panel = next((k for k in row.iter("node") if _rid(k) == "panelItemList"), None)
    out = []
    if panel is None:
        return out
    for item in panel:
        title = next((k.get("text") for k in item if _rid(k) == "title" and k.get("text")), None)
        if title is None:
            continue
        out.append(_control(item, title, own=False))
    return out


def parse_screen(xml_text):
    """The visible part of a Sony settings list as plain data.

    Each row: title, header (a collapsible section header), label (a non-focusable section title such as Basic),
    focused, enabled, expanded (the focused row shows its description and sub-options), control (the row's own value),
    subs (sub-options), and focus ('row', a sub-option's title, or None while focus is elsewhere or moving).
    """
    start = xml_text.find("<?xml")
    root = ET.fromstring(xml_text[start:] if start >= 0 else xml_text)
    top = root.find("node")
    screen = {"package": top.get("package") if top is not None else None,
              "title": next((n.get("text") for n in root.iter("node") if _rid(n) == "decor_title"), None),
              "rows": []}
    lst = next((n for n in root.iter("node") if _rid(n) == "list"), None)
    if lst is None:
        return screen
    for r in lst:
        header = _has(r, ("collapse_indicator",))
        expanded = _has(r, ("expandedContainer",))
        title = _first_text(r, ("expandedTitle", "title"))
        focusable = r.get("focusable") == "true"
        focused = r.get("focused") == "true" or any(
            k.get("focused") == "true" for k in r.iter("node") if _rid(k) in ("normalContainer", "expandedContainer"))
        row = {"title": title or "", "header": header, "label": bool(title) and not focusable and not header,
               "focused": focused, "expanded": expanded,
               "enabled": all(k.get("enabled") != "false" for k in r.iter("node") if _rid(k) in ("title", "expandedTitle")),
               "control": None, "subs": [], "focus": None}
        if not header and title:
            subs = _subs(r)
            # Motionflow and Reality Creation show their own picker as the first sub-option, under the same title.
            if subs and norm(subs[0]["title"]) == norm(title):
                row["control"], row["subs"], row["own_in_subs"] = subs[0], subs[1:], True
            else:
                own = next((k for k in r.iter("node") if _rid(k) == "contentsContainer"), None)
                row["control"] = _control(own if own is not None else r, title, own=True)
                row["subs"], row["own_in_subs"] = subs, False
            if row["control"]["kind"] == "action":
                row["control"] = None
            if focused:
                sub = next((s for s in row["subs"] if s["marked"]), None)
                row["focus"] = sub["title"] if sub else "row"
        screen["rows"].append(row)
    return screen


def focused_row(screen):
    return next((r for r in screen["rows"] if r["focused"]), None)


def fingerprint(screen):
    r = focused_row(screen)
    if r is None:
        return None
    ctl = focused_control(r)
    return (r["title"], r["focus"], ctl["value"] if ctl else None)


def focused_control(row):
    if row is None or row["focus"] is None:
        return None
    if row["focus"] == "row":
        return row["control"]
    return next((s for s in row["subs"] if s["title"] == row["focus"]), None)


RESET_PREFIX = "reset picture settings for "


def active_mode(screen):
    """The picture mode really in use, from the "Reset picture settings for <mode>" row.

    The Picture mode picker cannot show a mode it does not list: in an app's Calibrated mode (switched on by
    "<App> calibrated mode") it shows its first entry instead, so its text alone is not the truth.
    """
    for r in screen["rows"]:
        t = norm(r["title"])
        if t.startswith(RESET_PREFIX):
            return r["title"][len(RESET_PREFIX):].strip()
    r = next((r for r in screen["rows"] if norm(r["title"]) == norm("Picture mode")), None)
    return r["control"]["value"] if r and r["control"] else None


def header_collapsed(screen, row):
    """A header whose next row is another header (or nothing) shows no content: it is collapsed."""
    rows = screen["rows"]
    i = rows.index(row)
    nxt = rows[i + 1] if i + 1 < len(rows) else None
    return nxt is None or nxt["header"]


# ---- what to press -----------------------------------------------------------------------------------------

def parse_target(spec):
    """'Section/Row[/Sub]=Value' -> (section or None, row, sub or None, value)."""
    if "=" not in spec:
        raise ValueError(f"{spec!r}: expected Path=Value")
    path, value = spec.split("=", 1)
    parts = [p.strip() for p in path.split("/") if p.strip()]
    if not parts or not value.strip():
        raise ValueError(f"{spec!r}: empty path or value")
    if len(parts) == 1:
        return (None, parts[0], None, value.strip())
    if len(parts) == 2:
        return (parts[0], parts[1], None, value.strip())
    if len(parts) == 3:
        return (parts[0], parts[1], parts[2], value.strip())
    raise ValueError(f"{spec!r}: a path has at most Section/Row/Sub-option")


def path_of(section, row, sub=None):
    return "/".join(p for p in (section, row, sub) if p)


def matches(target, section, row_title):
    t_section, t_row = target[0], target[1]
    return norm(t_row) == norm(row_title) and (t_section is None or norm(t_section) == norm(section))


def slider_number(value):
    """A slider's shown value as an int, or 'max'/'min' for the words the TV shows at the ends."""
    v = norm(value)
    if v in ("max", "min"):
        return v
    try:
        return int(v.replace("+", ""))
    except ValueError:
        return None


def slider_keys(current, want):
    """How many presses and which way, or None when the shown value alone cannot tell (Max/Min vs a number)."""
    c, w = slider_number(current), slider_number(want)
    if c is None or w is None:
        raise ValueError(f"not a slider value: {current!r} -> {want!r}")
    if c == w:
        return ("", 0)
    if w == "max":
        return ("RIGHT", 1)
    if w == "min":
        return ("LEFT", 1)
    if c == "max":
        return ("LEFT", 1)       # learn the number below Max first
    if c == "min":
        return ("RIGHT", 1)
    return ("RIGHT", w - c) if w > c else ("LEFT", c - w)


def same_value(control, want):
    if control is None or control["value"] is None:
        return False
    if control["kind"] == "slider":
        return slider_number(control["value"]) == slider_number(want)
    return norm(control["value"]) == norm(want)


# ---- the record --------------------------------------------------------------------------------------------

def record_path(host):
    if os.environ.get("SONYUI_RECORD"):
        return Path(os.environ["SONYUI_RECORD"])
    tag = re.sub(r"[^A-Za-z0-9.-]", "_", host or "no-host")
    return Path.home() / ".cleanyourtv" / f"sonyui-{tag}.json"


def load_record(path):
    if not path.exists():
        return {"version": RECORD_VERSION, "labels": {}}
    with open(path, encoding="utf-8") as f:
        record = json.load(f)
    if record.get("version") != RECORD_VERSION or not isinstance(record.get("labels"), dict):
        raise SystemExit(f"{path}: unknown record format; move it aside deliberately.")
    return record


def write_record(path, record):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(record, f, indent=2, ensure_ascii=False)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def default_label(app, dolby_vision):
    return f"{app or 'unknown'}-{'dv' if dolby_vision else 'sdr'}"


def check_label(record, label, app, dolby_vision):
    """None when the label may be used over this app and signal, else why not.

    A label holds one memory's "before" values. Used over another app or signal, its first-value-wins entries
    would hide the other memory's values for good, and restore would write them into the wrong one.
    """
    lab = record["labels"].get(label)
    if not lab:
        return None
    if lab.get("app") and app and lab["app"] != app:
        return f"label {label!r} was recorded over {lab['app']}, not {app}"
    if lab.get("dolby_vision") is not None and bool(lab["dolby_vision"]) != bool(dolby_vision):
        return (f"label {label!r} was recorded {'with' if lab['dolby_vision'] else 'without'} Dolby Vision on "
                f"screen, and it is {'on' if dolby_vision else 'off'} now")
    return None


def label_context(record, label, app, dolby_vision):
    """What a label was recorded over (first run wins): the app in front and whether it was Dolby Vision."""
    lab = record["labels"].setdefault(label, {"entries": []})
    lab.setdefault("app", app)
    lab.setdefault("dolby_vision", bool(dolby_vision))
    return lab


def remember(record, label, mode, path, value, when=None):
    """First value wins per (label, picture mode, path). Returns True when this call added it."""
    lab = record["labels"].setdefault(label, {"entries": []})
    for e in lab["entries"]:
        if norm(e["mode"]) == norm(mode) and norm(e["path"]) == norm(path):
            return False
    lab["entries"].append({"mode": mode, "path": path, "value": value,
                           "time": when or time.strftime("%Y-%m-%dT%H:%M:%S%z")})
    return True


def is_mode_path(path):
    return norm(path) == norm(f"{FIRST_SECTION}/{PICTURE_MODE}")


def is_calibrated_switch(path):
    """'Basic/Prime Video calibrated mode' and the like: the switch that puts an app in Calibrated."""
    return norm(path.rsplit("/", 1)[-1]).endswith("calibrated mode")


def restore_passes(record, label):
    """([(picture mode, [(path, value), ...]), ...], mode to finish on, [(switch path, value), ...]).

    Each recorded mode gets a pass: switch to it, put its rows back. The picture mode row is recorded under the
    mode that was active, so that is the mode to finish on. Calibrated-mode switches come last: switching one
    on changes the mode by itself, and it needs no mode to be selected first.
    """
    lab = record["labels"].get(label)
    if not lab:
        return [], None, []
    passes, finish, switches = [], None, []
    for e in lab["entries"]:
        if is_mode_path(e["path"]):
            finish = finish or e["value"]
            continue
        if is_calibrated_switch(e["path"]):
            switches.append((e["path"], e["value"]))
            continue
        for mode, rows in passes:
            if norm(mode) == norm(e["mode"]):
                rows.append((e["path"], e["value"]))
                break
        else:
            passes.append((e["mode"], [(e["path"], e["value"])]))
    return passes, finish, switches


# ---- the TV ------------------------------------------------------------------------------------------------

class Panel:
    """One open settings panel on the TV, driven over a single ADB connection."""

    def __init__(self, dev, log=print, settle=0.9):
        self.dev, self.log, self.settle = dev, log, settle

    def sh(self, command, timeout=60.0):
        return self.dev.shell(command, read_timeout_s=timeout) or ""

    def key(self, name):
        if name not in ("DPAD_DOWN", "DPAD_UP", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "BACK"):
            raise ValueError(name)
        self.sh(f"input keyevent KEYCODE_{name}")
        time.sleep(self.settle)

    def dump(self):
        for _ in range(4):
            self.sh("uiautomator dump /sdcard/cyt-ui.xml >/dev/null 2>&1")
            text = self.sh("cat /sdcard/cyt-ui.xml")
            if "<?xml" in text and "</hierarchy>" in text:
                return text
            time.sleep(0.5)
        raise SystemExit("uiautomator dump failed four times; is the TV awake?")

    def screen(self):
        """A dump taken once focus has settled: two in a row that agree, so a key is never sent mid-animation."""
        prev = None
        for _ in range(5):
            s = parse_screen(self.dump())
            if s["package"] != SETTINGS_PACKAGE:
                raise SystemExit(f"the settings panel is no longer on screen (now: {s['package']}); stopped.")
            fp = fingerprint(s)
            if fp is not None and fp == prev:
                return s
            prev = fp
            time.sleep(0.4)
        raise SystemExit("the settings panel did not settle (focus kept moving); stopped.")

    def open(self, panel="picture"):
        """A fresh panel with focus on its first row.

        Sony's panel comes back where it was left when it is reopened soon after; a new task
        (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK) starts it at the top with every section folded.
        """
        action = PANELS[panel]
        self.sh(f"am start -W -f 0x10008000 -a {action} >/dev/null 2>&1")
        time.sleep(1.5)
        s = self.screen()
        r = focused_row(s)
        # The first row with a value or action; a section header above it (Basic is one over apps) does not count.
        focusable = [x for x in s["rows"] if not x["label"] and not x["header"] and x["title"]]
        if r is None or not focusable or focusable[0] is not r:
            raise SystemExit(f"opened {panel} but focus is not on its first row "
                             f"({r and r['title']!r}); stopped before touching anything.")
        return s

    def close_if_open(self):
        """Back closes Sony's panel from any depth; sent only while the panel is in front, never to an app."""
        try:
            self.sh("uiautomator dump /sdcard/cyt-ui.xml >/dev/null 2>&1")
            text = self.sh("cat /sdcard/cyt-ui.xml")
            if "<?xml" in text and parse_screen(text)["package"] == SETTINGS_PACKAGE:
                self.sh("input keyevent KEYCODE_BACK")
        except Exception as e:     # closing is best effort; the error that stopped the run matters more
            self.log(f"  could not check the panel before closing it: {e}")

    # -- moving --

    def expand_if_collapsed(self, s, row):
        if not header_collapsed(s, row):
            return s
        self.key("DPAD_CENTER")
        s = self.screen()
        r = focused_row(s)
        if r is not None and r["header"] and header_collapsed(s, r):
            # The header's content was only off screen and Center folded it: open it again.
            self.key("DPAD_CENTER")
            s = self.screen()
        return s

    def down(self, s):
        """Down until the focus changes; None at the end of the list."""
        before = fingerprint(s)
        for attempt in range(4):
            self.key("DPAD_DOWN")
            if attempt:
                time.sleep(0.6 * attempt)   # a key sent while the list scrolls can be dropped
            s2 = self.screen()
            if fingerprint(s2) != before:
                return s2
        return None

    # -- changing --

    def control_checker(self, row_title, sub):
        def control_of(screen):
            r = focused_row(screen)
            if r is None or norm(r["title"]) != norm(row_title):
                raise SystemExit(f"focus left {row_title!r} while changing it; stopped (focused: {r and r['title']!r}).")
            if sub is None and r["focus"] != "row":
                raise SystemExit(f"{row_title!r}: focus is on its sub-option {r['focus']!r}, not on the row; stopped.")
            if sub is not None and norm(r["focus"] or "") != norm(sub):
                raise SystemExit(f"{row_title!r}: focus is on {r['focus']!r}, not on {sub!r}; stopped.")
            return focused_control(r)
        return control_of

    def set_control(self, s, row_title, sub, want, before=None):
        """Change the focused control to `want`. Returns (screen, old value).

        Focus is checked before every key. before(old) runs once, right before the first key that changes
        anything, so the old value is on record even if the change stops halfway.
        """
        control_of = self.control_checker(row_title, sub)
        ctl = control_of(s)
        if ctl is None:
            raise SystemExit(f"{path_of(None, row_title, sub)}: this row has no value to change.")
        old = ctl["value"]
        if same_value(ctl, want):
            return s, old
        once = _Once(lambda: before(old) if before else None)
        if ctl["kind"] == "slider":
            return self._slide(s, control_of, want, once), old
        return self._pick(s, control_of, want, old, once), old

    def _slide(self, s, control_of, want, before):
        """Press towards `want`, a few keys at a time, re-reading the value after each batch.

        The TV shows a slider's ends as Max and Min. The number behind a word is learned only from single
        presses (the value next to Max is Max - 1), and the last steps are taken one at a time, so a numeric
        target never bounces around the word. The total number of presses is capped.
        """
        w = slider_number(want)
        if w is None:
            raise SystemExit(f"{want!r} is not a slider value (a number, Max or Min)")
        ends, history, presses, stalls = {}, [], 0, 0
        while True:
            ctl = control_of(s)
            v = slider_number(ctl["value"])
            if v is None:
                raise SystemExit(f"cannot read the slider value {ctl['value']!r}")
            if v == w or (isinstance(w, int) and ends.get(v) == w):
                return s
            if isinstance(w, int) and (("max" in ends and w > ends["max"]) or ("min" in ends and w < ends["min"])):
                raise SystemExit(f"{want} is outside this slider ({ends.get('min', '?')} to {ends.get('max', '?')}); "
                                 f"left at {ctl['value']}.")
            if isinstance(w, str):
                direction, batch = ("RIGHT" if w == "max" else "LEFT"), 5
            elif isinstance(v, int):
                direction = "RIGHT" if w > v else "LEFT"
                batch = 1 if abs(w - v) <= 2 else min(abs(w - v) - 2, 5)
            else:
                direction, batch = ("LEFT" if v == "max" else "RIGHT"), 1
            before()
            for _ in range(batch):
                self.key(f"DPAD_{direction}")
            presses += batch
            s2 = self.screen()
            new = slider_number(control_of(s2)["value"])
            if batch == 1:
                if isinstance(v, int) and new in ("max", "min"):
                    ends[new] = v + (1 if new == "max" else -1)
                if v in ("max", "min") and isinstance(new, int):
                    ends[v] = new + (1 if v == "max" else -1)
            stalls = stalls + 1 if new == v else 0
            if stalls >= 2:
                raise SystemExit(f"slider stuck at {ctl['value']!r}, wanted {want!r} (outside its range?); stopped.")
            history.append(new)
            if len(history) >= 8 and len(set(history[-8:])) <= 2:
                raise SystemExit(f"slider keeps swinging between {sorted(map(str, set(history[-8:])))}; stopped.")
            if presses > 120:
                raise SystemExit(f"slider did not reach {want!r} in 120 presses; stopped.")
            s = s2

    def _pick(self, s, control_of, want, old, before):
        seen = [old]
        for direction, arrow in (("RIGHT", "right"), ("LEFT", "left")):
            for _ in range(16):
                ctl = control_of(s)
                if same_value(ctl, want):
                    return s
                if not ctl[arrow]:
                    break
                before()
                self.key(f"DPAD_{direction}")
                s2 = self.screen()
                v = control_of(s2)["value"]
                if v == ctl["value"]:
                    self.key(f"DPAD_{direction}")   # once more: the first press may have been dropped
                    s2 = self.screen()
                    v = control_of(s2)["value"]
                    if v == ctl["value"]:
                        break
                if v not in seen:
                    seen.append(v)
                s = s2
        # Not offered: put the old value back rather than leave whatever the last key landed on.
        if not same_value(control_of(s), old):
            self._pick_back(s, control_of, old)
        raise NotOffered(f"{want!r} is not offered here; the TV offers: {', '.join(map(str, seen))}. "
                         f"Left at {old!r}.")

    def _pick_back(self, s, control_of, old):
        for direction, arrow in (("RIGHT", "right"), ("LEFT", "left")):
            for _ in range(16):
                ctl = control_of(s)
                if same_value(ctl, old) or not ctl[arrow]:
                    break
                self.key(f"DPAD_{direction}")
                s = self.screen()
            if same_value(control_of(s), old):
                return

    def select_mode(self, s, r, want, before=None):
        """Switch Picture mode to `want` and prove it from the Reset row. Returns (screen, old mode).

        Raises ModeNotReached, having changed nothing, when the row is greyed out or `want` is Calibrated.
        In an app's Calibrated mode the picker shows a stand-in (its first entry) while the Reset row names
        Calibrated; asking for the stand-in then needs a step away and back to really select it.
        """
        old = active_mode(s)
        if old and norm(old) == norm(want):
            return s, old
        if not r["enabled"]:
            raise ModeNotReached(f"Picture mode is greyed out here (is an app's calibrated mode on?); "
                                 f"it stays {old}")
        if norm(want) == norm(CALIBRATED):
            raise ModeNotReached(f"{CALIBRATED} is not in the Picture mode list; only the app's "
                                 f"'calibrated mode' switch selects it. It stays {old}")
        control_of = self.control_checker(r["title"], None)
        ctl = control_of(s)
        once = _Once(lambda: before(old) if before else None)
        if same_value(ctl, want):
            # The stand-in case: the picker already reads `want` but the TV is in another mode.
            first, back = ("RIGHT", "LEFT") if ctl["right"] else ("LEFT", "RIGHT")
            once()
            self.key(f"DPAD_{first}")
            control_of(self.screen())
            self.key(f"DPAD_{back}")
            s = self.screen()
        else:
            try:
                s = self._pick(s, control_of, want, ctl["value"], once)
            except NotOffered as e:
                raise ModeNotReached(f"{e} It stays {old}")
        now = active_mode(s)
        if norm(now or "") != norm(want):
            raise ModeNotReached(f"asked for {want}, but the TV reports {now}")
        return s, old

    # -- one pass down the list --

    def walk(self, s, targets, before_change=None, on_change=None, read_only=False, sink=None):
        """Walk down from the top, doing every target on the way. Returns the targets not found.

        targets: (section, row, sub, value) tuples. A Picture mode target is a precondition for the rest: when it
        cannot be reached, ModeNotReached is raised before any other row is touched.
        before_change(section, row, sub, old, mode) runs right before the first key of each change (for the record);
        on_change(section, row, sub, old, new, mode) runs after it. sink(section, row, screen) receives every row
        seen with focus on the row itself (for `read`).
        """
        pending = list(targets)
        mode_targets = [t for t in pending if t[2] is None and norm(t[1]) == norm(PICTURE_MODE)]
        section = FIRST_SECTION
        mode = None
        visited = set()
        reprocess = False
        while True:
            r = focused_row(s)
            if r is None:
                raise SystemExit("lost focus in the settings list")
            mode = active_mode(s) or mode
            if r["header"]:
                section = r["title"]
                wanted = read_only or any(t[0] is None or norm(t[0]) == norm(section) for t in pending)
                if wanted and r["title"] not in visited:
                    visited.add(r["title"])
                    s = self.expand_if_collapsed(s, r)
                    r = focused_row(s)
            else:
                if sink is not None and r["focus"] == "row":
                    sink(section, r, s)
                here = [t for t in pending if matches(t, section, r["title"])]
                order = [norm(x["title"]) for x in r["subs"]]
                here.sort(key=lambda t: -1 if t[2] is None else (order.index(norm(t[2])) if norm(t[2]) in order else 99))
                for t in here:
                    if read_only:
                        pending.remove(t)
                        continue
                    if t in mode_targets:
                        def before_mode(old, _section=section, _title=r["title"]):
                            if before_change:
                                before_change(_section, _title, None, old, old)
                        s, old = self.select_mode(s, r, t[3], before_mode)
                        pending.remove(t)
                        new = active_mode(s)
                        if norm(old or "") != norm(new or ""):
                            if on_change:
                                on_change(section, r["title"], None, old, new, old)
                            self.log(f"  {path_of(section, r['title'])}: {old} -> {new}")
                        else:
                            self.log(f"  {path_of(section, r['title'])}: already {new}")
                        mode = new
                        r = focused_row(s)
                        continue
                    if mode_targets and any(m in pending for m in mode_targets):
                        raise SystemExit("internal: a row came before its Picture mode step")
                    if not r["enabled"]:
                        self.log(f"  skip {path_of(section, r['title'], t[2])}: greyed out here")
                        pending.remove(t)
                        continue
                    sub_title = None
                    if t[2] is not None:
                        moved = self._to_sub(s, r["title"], t[2])
                        if moved is None:
                            # Focus left the row before the sub-option came up: leave it pending (reported as
                            # not found) and look at wherever focus is now, without another Down.
                            s = self.screen()
                            reprocess = True
                            break
                        s = moved
                        sub_title = focused_row(s)["focus"]     # the TV's spelling, for the record

                    def before(old, _section=section, _title=r["title"], _sub=sub_title, _mode=mode):
                        if before_change:
                            before_change(_section, _title, _sub, old, _mode)
                    try:
                        s, old = self.set_control(s, r["title"], sub_title, t[3], before)
                    except NotOffered as e:
                        raise SystemExit(f"{path_of(section, r['title'], sub_title)}: {e}")
                    new = focused_control(focused_row(s))["value"]
                    pending.remove(t)
                    changed = norm(old or "") != norm(new or "")
                    if on_change and changed:
                        on_change(section, r["title"], sub_title, old, new, mode)
                    self.log(f"  {path_of(section, r['title'], sub_title)}: "
                             + (f"{old} -> {new}" if changed else f"already {new}"))
                    r = focused_row(s)
            if not pending and not read_only:
                return []
            if reprocess:
                reprocess = False
                continue
            nxt = self.down(s)
            if nxt is None:
                return pending
            s = nxt

    def _to_sub(self, s, row_title, sub):
        for _ in range(8):
            r = focused_row(s)
            if r is None or norm(r["title"]) != norm(row_title):
                return None
            if r["focus"] and norm(r["focus"]) == norm(sub):
                return s
            if not any(norm(x["title"]) == norm(sub) for x in r["subs"]):
                raise SystemExit(f"{row_title!r} has no sub-option {sub!r} here "
                                 f"(it has: {', '.join(x['title'] for x in r['subs']) or 'none'}).")
            s = self.down(s)
            if s is None:
                return None
        return None


class _Once:
    """Calls fn the first time it is called, never again."""

    def __init__(self, fn):
        self.fn, self.done = fn, False

    def __call__(self):
        if not self.done:
            self.done = True
            self.fn()


# ---- commands ----------------------------------------------------------------------------------------------

def connect():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    try:
        import tv
    except ImportError as e:
        raise SystemExit(f"cannot load tools/tv.py ({e}).\nrun: python3 -m venv tools/venv && "
                         "tools/venv/bin/pip install -r tools/requirements.txt")
    return tv.connect()


def foreground_app(dev):
    """The package of the activity in front, before a settings panel covers it."""
    out = dev.shell("dumpsys activity activities | grep -m1 mResumedActivity", read_timeout_s=60) or ""
    m = re.search(r" ([A-Za-z0-9_.]+)/", out)
    return m.group(1) if m else None


def is_dolby_vision(screen):
    r = next((r for r in screen["rows"] if norm(r["title"]) == norm(PICTURE_MODE)), None)
    names = [active_mode(screen) or "", (r and r["control"] and r["control"]["value"]) or ""]
    return any("dolby vision" in norm(n) for n in names)


def cmd_read(args):
    out_json = None
    if args[:1] == ["--json"]:
        if len(args) < 2:
            raise SystemExit("usage: sonyui.py read [--json FILE]")
        out_json = args[1]
    dev = connect()
    rows = []
    panel = Panel(dev)
    try:
        s = panel.open("picture")

        def sink(section, r, screen):
            if any(x["section"] == section and x["title"] == r["title"] for x in rows):
                return
            ctl = r["control"]
            rows.append({"section": section, "title": r["title"], "value": ctl and ctl["value"],
                         "kind": ctl and ctl["kind"], "enabled": r["enabled"],
                         "subs": [{"title": x["title"], "value": x["value"], "kind": x["kind"]} for x in r["subs"]]})
        print(f"picture mode in use: {active_mode(s)}")
        panel.walk(s, [], read_only=True, sink=sink)
    finally:
        panel.close_if_open()
        dev.close()
    for x in rows:
        print(f"{x['section'] + '/' + x['title']:52} {x['value'] or '':22}{'' if x['enabled'] else ' (greyed)'}")
        for sub in x["subs"]:
            print(f"    {sub['title']:48} {sub['value'] or ''}")
    if out_json:
        Path(out_json).write_text(json.dumps(rows, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return 0


def run_set(specs, label=None, dry_run=False, record_changes=True, host=None, force=False, dev=None):
    """Apply specs in one pass. 0 when all done, 1 when rows were not found, 2 when the picture mode was not reached."""
    targets = [parse_target(x) for x in specs]
    host = host if host is not None else os.environ.get("TV_HOST", "")
    if dry_run:
        for t in targets:
            print(f"would set {path_of(*t[:3])} = {t[3]}")
        return 0
    path = record_path(host)
    record = load_record(path)
    own = dev is None
    dev = dev or connect()
    panel = Panel(dev)
    try:
        app = foreground_app(dev)
        s = panel.open("picture")
        dv = is_dolby_vision(s)
        lab = label or default_label(app, dv)
        if not LABEL_RE.fullmatch(lab):
            raise SystemExit(f"bad label {lab!r}")
        if record_changes:
            problem = check_label(record, lab, app, dv)
            if problem and not force:
                raise SystemExit(f"{problem}; use another --label (or --force).")
            label_context(record, lab, app, dv)
        print(f"label {lab} over {app}{' (Dolby Vision)' if dv else ''}; record {path}")

        def before_change(section, row, sub, old, mode):
            if record_changes and old is not None and remember(record, lab, mode or "?", path_of(section, row, sub), old):
                write_record(path, record)
        try:
            missing = panel.walk(s, targets, before_change=before_change)
        except ModeNotReached as e:
            print(f"picture mode not reached: {e}. Nothing after it was changed.", file=sys.stderr)
            return 2
    finally:
        panel.close_if_open()
        if own:
            dev.close()
    for t in missing:
        print(f"not found: {path_of(*t[:3])}", file=sys.stderr)
    return 1 if missing else 0


def cmd_restore(args):
    label, dry_run, force = None, False, False
    i = 0
    while i < len(args):
        if args[i] == "--label":
            label = option_value(args, i, "--label")
            i += 2
        elif args[i] == "--dry-run":
            dry_run, i = True, i + 1
        elif args[i] == "--force":
            force, i = True, i + 1
        else:
            raise SystemExit(f"unknown option {args[i]!r}")
    host = os.environ.get("TV_HOST", "")
    record = load_record(record_path(host))
    if label is None:
        names = ", ".join(record["labels"]) or "none recorded"
        raise SystemExit(f"restore needs --label: each label belongs to one app and signal ({names}).")
    if label not in record["labels"]:
        raise SystemExit(f"nothing recorded under {label!r}")
    passes, finish, switches = restore_passes(record, label)
    meta = record["labels"][label]
    print(f"{label} (recorded over {meta.get('app')}{', Dolby Vision' if meta.get('dolby_vision') else ''}):")
    for mode, rows in passes:
        print(f"  in {mode}: " + ", ".join(f"{p}={v}" for p, v in rows))
    print(f"  then Picture mode back to {finish}" if finish else "  the picture mode was not changed by this label")
    for p, v in switches:
        print(f"  then {p}={v}")
    if dry_run:
        return 0
    dev = connect()
    rc = 0
    try:
        app = foreground_app(dev)
        panel = Panel(dev)
        try:
            dv = is_dolby_vision(panel.open("picture"))
        finally:
            panel.close_if_open()
        if not force:
            if meta.get("app") and app != meta["app"]:
                raise SystemExit(f"{label}: recorded over {meta['app']}, but {app} is in front; open it first.")
            if meta.get("dolby_vision") is not None and dv != bool(meta["dolby_vision"]):
                raise SystemExit(f"{label}: recorded {'with' if meta['dolby_vision'] else 'without'} Dolby Vision on "
                                 "screen; play matching content first.")
        print("  make sure the same kind of picture (SDR, HDR or Dolby Vision) is on screen as when it was recorded.")
        for mode, rows in passes:
            code = run_set([f"{FIRST_SECTION}/{PICTURE_MODE}={mode}"] + [f"{p}={v}" for p, v in rows],
                           label=label, record_changes=False, host=host, dev=dev)
            if code:
                print(f"  {mode}: not restored (see above)", file=sys.stderr)
                rc = 1
        if finish:
            code = run_set([f"{FIRST_SECTION}/{PICTURE_MODE}={finish}"], label=label, record_changes=False,
                           host=host, dev=dev)
            if code:
                if norm(finish) == norm(CALIBRATED) and switches:
                    print("  Calibrated comes back with the calibrated-mode switch below.")
                else:
                    rc = 1
        if switches:
            code = run_set([f"{p}={v}" for p, v in switches], label=label, record_changes=False, host=host, dev=dev)
            rc = rc or code
    finally:
        dev.close()
    return rc


def option_value(args, i, name):
    if i + 1 >= len(args) or args[i + 1].startswith("-"):
        raise SystemExit(f"{name} needs a value")
    return args[i + 1]


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return 0 if args else 2
    cmd, rest = args[0], args[1:]
    if cmd in ("set", "apply"):
        label, dry_run, force, specs = None, False, False, []
        i = 0
        while i < len(rest):
            if rest[i] == "--label":
                label, i = option_value(rest, i, "--label"), i + 2
            elif rest[i] == "--dry-run":
                dry_run, i = True, i + 1
            elif rest[i] == "--force":
                force, i = True, i + 1
            elif rest[i].startswith("--"):
                raise SystemExit(f"unknown option {rest[i]!r}")
            else:
                specs.append(rest[i])
                i += 1
        if cmd == "apply":
            if len(specs) != 1:
                raise SystemExit("usage: sonyui.py apply STEPS.json [--label L] [--dry-run] [--force]")
            steps = json.loads(Path(specs[0]).read_text(encoding="utf-8"))
            if steps.get("panel", "picture") != "picture":
                raise SystemExit("only the picture panel is supported")
            specs = [f"{k}={v}" for k, v in steps.get("set", {}).items()]
        if not specs:
            raise SystemExit("nothing to set")
        for spec in specs:
            try:
                parse_target(spec)
            except ValueError as e:
                raise SystemExit(str(e))
        if label is not None and not LABEL_RE.fullmatch(label):
            raise SystemExit(f"bad label {label!r}")
        return run_set(specs, label=label, dry_run=dry_run, force=force)
    if cmd == "read":
        return cmd_read(rest)
    if cmd == "restore":
        return cmd_restore(rest)
    if cmd == "show-record":
        path = record_path(os.environ.get("TV_HOST", ""))
        print(path)
        print(json.dumps(load_record(path), indent=2, ensure_ascii=False))
        return 0
    if cmd in ("dump", "shot") and len(rest) == 1:
        dev = connect()
        try:
            if cmd == "dump":
                Path(rest[0]).write_text(Panel(dev).dump(), encoding="utf-8")
            else:
                dev.shell("screencap -p /sdcard/cyt-shot.png", read_timeout_s=60)
                dev.pull("/sdcard/cyt-shot.png", rest[0])
        finally:
            dev.close()
        print(rest[0])
        return 0
    raise SystemExit(f"unknown command {cmd!r}; see --help")


if __name__ == "__main__":
    offline = len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "show-record") or "--dry-run" in sys.argv[2:]
    if not os.environ.get("TV_HOST") and not offline:
        sys.exit("Set TV_HOST to your TV's IP address, e.g.  TV_HOST=192.168.1.50 tools/sonyui.py read")
    sys.exit(main())
