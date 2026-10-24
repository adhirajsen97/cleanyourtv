#!/usr/bin/env python3
"""Picture settings on a Sony BRAVIA over the TV's own REST interface, recorded first so undo means undo.

    bravia.py pair                                      one-time pairing; a person at the TV types the PIN it shows
    bravia.py status [--json]                           power saving, the signal on screen, every picture target
    bravia.py get <target>                              one target: its value, whether it is adjustable, candidates
    bravia.py call <service> <method> '<params json>' [version]
                                                        any method as is (a write made this way is not recorded)
    bravia.py picture apply [--label NAME] [--set target=value ...] [--dry-run]
    bravia.py picture restore [--label NAME] [--dry-run]
    bravia.py picture show-record

Set TV_HOST to the TV's IP address. Standard library only: tools/venv/bin/python -I tools/bravia.py, or python3.

Reads need no authorisation; writes do. `pair` registers this computer with the TV: the TV shows a 4-digit PIN,
and the cookie it hands back for it (about two weeks) is kept in ~/.cleanyourtv/bravia-<TV_HOST>.json, mode 0600.
Instead of pairing, BRAVIA_PSK can hold the Pre-Shared Key set on the TV under Network & Internet > Home network
> IP control.

`picture apply` switches to the mode worth tuning for the signal on screen, Custom for SDR and HDR or Dolby Vision
Bright for Dolby Vision, reads the TV again (what is adjustable differs per mode), and then sets, one at a time and
only where the TV says the setting is adjustable: light sensor off, auto picture mode off, brightness to its
maximum, contrast 90, peak luminance (xtendedDynamicRange) high, HDR mode auto, colour space auto. Power saving goes
off last. --set replaces a profile value or adds a target (--set colorTemperature=neutral, --set
pictureMode=cinema); a value the TV does not offer stops the run before anything is written. Afterwards the TV is
read again and a value that did not take is named, with exit status 1; so is a --set target the TV does not let
change in that mode.

Before the first write, ~/.cleanyourtv/bravia-picture-<TV_HOST>.json records the picture mode that was active and,
read right after the switch, every target's value in the mode being tuned, under a label: dv for Dolby Vision,
otherwise sdr-hdr, since the API cannot tell SDR from HDR10 (pass --label hdr10 with HDR10 on screen to keep its own
record). A label holds one kind of content: one recorded with Dolby Vision is refused for SDR and the other way
round. Power saving is recorded when this tool turns it off. The first value recorded wins per label and picture
mode, like tools/recovery.py: a second run under the same label never records the tuned state as the original. A
run that the TV refuses before anything changed leaves the record as it was.

`picture restore` (the label of what is on screen unless --label names one) switches to each recorded mode, puts
its values back, returns to the mode that was active and puts power saving back; power saving is one setting for
the whole TV, so any label's restore does it. A mode the TV will not switch to keeps its values unwritten, so none
lands in another mode, and the rest still runs. Labels that tune the same mode (sdr and hdr10 both tune Custom)
are undone in reverse order, the last one applied first: a later label may have found that mode already tuned.
"""
import argparse
import base64
import collections
import copy
import datetime
import email.utils
import itertools
import json
import os
from pathlib import Path
import re
import socket
import sys
import time
import urllib.error
import urllib.request
import uuid

RECORD_VERSION = 1
# TV_HOST and the names given to `call` end up in a URL: an address or a host name, nothing that could carry a
# path or a query.
HOST_RE = re.compile(r"(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+)(:\d{1,5})?")
NAME_RE = re.compile(r"[A-Za-z][A-Za-z0-9]{0,63}")
VERSION_RE = re.compile(r"\d{1,3}\.\d{1,3}")
LABEL_RE = re.compile(r"[A-Za-z0-9_.-]{1,32}")
# ASCII digits only: \d also matches other scripts' digits, which the Basic header cannot carry.
PIN_RE = re.compile(r"[0-9]{4}")
# Header values go out as they are, so only characters a header can carry: a stray newline must not add one.
PSK_RE = re.compile(r"[\x20-\x7e]+")
COOKIE_RE = re.compile(r"[\x21\x23-\x2b\x2d-\x3a\x3c-\x5b\x5d-\x7e]+")

# The profile, in the order it is written. Each entry is set only if the target exists and the TV says it is
# adjustable in the mode being tuned.
PROFILE = (
    # The sensor dims the picture to the room; it is why everything but Vivid looked dark.
    ("lightSensor", "off"),
    # Content-based mode switching would leave the tuned mode behind.
    ("autoPictureMode", "off"),
    ("brightness", "max"),
    # The bright-room value the reviews of this panel converge on.
    ("contrast", "90"),
    # The menu's "Peak luminance"; anything below High lowers the ceiling for highlights.
    ("xtendedDynamicRange", "high"),
    ("hdrMode", "auto"),
    ("colorSpace", "auto"),
)
TUNED_MODES = {"dv": "dvBright", "sdr-hdr": "custom"}
# Put back last on restore: content-based mode switching, switched on halfway, could move the TV to another mode
# before the remaining values land.
RESTORE_LAST = ("autoPictureMode",)
ERROR_HINTS = {
    3: "Illegal Argument",
    7: "Illegal State: the TV is in standby, or what is on screen has no picture settings",
    12: "No Such Method on this TV",
    14: "this TV does not offer that version of the method",
    15: "Unsupported Operation",
    40004: "the TV did not take the setting",
}
NOT_PAIRED = ("not paired: writes need `bravia.py pair` with someone at the TV to read its PIN, or BRAVIA_PSK set to "
              "the Pre-Shared Key from Network & Internet > Home network > IP control on the TV")

Step = collections.namedtuple("Step", "target value action reason explicit")
Auth = collections.namedtuple("Auth", "headers note ok")


class PlanError(Exception):
    """The TV would not take what was asked; raised before anything is written."""


class ApiError(Exception):
    def __init__(self, code, message="", where=""):
        self.code = code
        self.message = message
        hint = ERROR_HINTS.get(code)
        text = f"error {code} ({message})" if message else f"error {code}"
        super().__init__(f"{where + ': ' if where else ''}{text}{'; ' + hint if hint and hint != message else ''}")


class AuthError(ApiError):
    """HTTP 401/403, or the same codes in the answer: the write needs pairing, or the pairing ran out."""


# ---- pure functions: what the TV said, what to write, what was recorded ----------------------------------------

def settings_of(result):
    """{target: entry} from a getPictureQualitySettings answer, in the TV's order."""
    entries = result or []
    if entries and isinstance(entries[0], list):
        entries = entries[0]
    return {e["target"]: e for e in entries if isinstance(e, dict) and isinstance(e.get("target"), str)}


def _index(settings):
    return settings if isinstance(settings, dict) else settings_of(settings)


def current(settings, target):
    entry = _index(settings).get(target)
    value = entry.get("currentValue") if entry else None
    return None if value is None else str(value)


def is_available(entry):
    return entry.get("isAvailable", True) is not False


def choices(entry):
    """(named values, None) for a list of choices, (None, (min, max, step)) for a slider, (None, None) without."""
    candidates = [c for c in (entry.get("candidate") or []) if isinstance(c, dict)]
    values = [str(c["value"]) for c in candidates if "value" in c]
    if values:
        return values, None
    for c in candidates:
        if "min" in c and "max" in c:
            try:
                return None, (int(c["min"]), int(c["max"]), int(c.get("step") or 1))
            except (TypeError, ValueError):
                break
    return None, None


def describe_choices(entry):
    values, span = choices(entry)
    if values:
        return ", ".join(values)
    if span:
        return f"{span[0]}..{span[1]}" + (f" step {span[2]}" if span[2] != 1 else "")
    return "-"


def mode_names(settings):
    entry = _index(settings).get("pictureMode")
    return (choices(entry)[0] or []) if entry else []


def signal_family(settings):
    """'dv' when the picture modes on offer are Dolby Vision ones, else 'sdr-hdr'; None without a pictureMode.

    The TV swaps the whole mode list for Dolby Vision content (dvDark, dvBright, dvVivid). An app's calibrated mode
    adds one more entry, "calibrated", to whichever list is showing (seen with Netflix playing Dolby Vision), so it
    does not count either way. SDR, HDR10 and HLG share one list, and hdrMode says "auto" for all of them, so the
    API cannot tell those apart.
    """
    entry = _index(settings).get("pictureMode")
    if entry is None:
        return None
    names = [n for n in (choices(entry)[0] or []) if n.lower() != "calibrated"]
    return "dv" if names and all(name.lower().startswith("dv") for name in names) else "sdr-hdr"


def tuned_mode(family, candidates):
    """The mode the profile tunes for this signal; PlanError with what is on offer when the TV lacks it."""
    want = TUNED_MODES.get(family, "custom")
    names = [str(c["value"]) if isinstance(c, dict) else str(c) for c in (candidates or [])
             if not isinstance(c, dict) or "value" in c]
    if want not in names:
        raise PlanError(f"this signal offers no {want!r} picture mode, so nothing was changed.\n"
                        f"Picture modes on offer: {', '.join(names) or 'none'}.\n"
                        "Name one with --set pictureMode=<mode> to tune it instead.")
    return want


def validate(settings, target, value):
    """The value as the TV spells it, or PlanError saying why the TV would not take it.

    Named values must be one of the candidates (case does not matter); sliders take a whole number within
    min..max on a step, or "max"/"min".
    """
    settings = _index(settings)
    entry = settings.get(target)
    if entry is None:
        raise PlanError(f"{target!r} is not a picture setting on this TV; it has: {', '.join(settings) or 'none'}")
    text = str(value).strip()
    values, span = choices(entry)
    if values is not None:
        for name in values:
            if name.lower() == text.lower():
                return name
        raise PlanError(f"{target} cannot be {text!r}; the TV offers: {', '.join(values)}")
    if span is not None:
        low, high, step = span
        if text.lower() in ("max", "min"):
            return str(high if text.lower() == "max" else low)
        if not re.fullmatch(r"[+-]?\d+", text):
            raise PlanError(f"{target} takes a whole number from {low} to {high}, not {text!r}")
        number = int(text)
        if not low <= number <= high:
            raise PlanError(f"{target} takes {low}..{high}; {number} is outside that")
        if step > 1 and (number - low) % step:
            raise PlanError(f"{target} moves in steps of {step} from {low}; {number} is not on one")
        return str(number)
    # The TV listed no candidates for it: send the value as given and let the re-read judge it.
    return text


def plan(settings, mode, overrides=None):
    """What apply writes once `mode` is active: the profile, then the --set targets, one Step per target.

    `settings` is the TV's answer read in `mode`: what is adjustable changes with the mode. action is "set",
    "already" (the TV has the value) or "skip" with a reason; explicit marks a --set target.
    """
    settings = _index(settings)
    overrides = dict(overrides or {})
    # The mode is the switch made before this plan, not one of its steps.
    overrides.pop("pictureMode", None)
    wanted = [(target, overrides.pop(target), True) if target in overrides else (target, value, False)
              for target, value in PROFILE]
    wanted += [(target, value, True) for target, value in overrides.items()]
    steps = []
    for target, value, explicit in wanted:
        entry = settings.get(target)
        if entry is None:
            steps.append(Step(target, str(value), "skip", "not on this TV", explicit))
            continue
        if not is_available(entry):
            steps.append(Step(target, str(value), "skip", f"not adjustable in {mode}", explicit))
            continue
        try:
            value = validate(settings, target, value)
        except PlanError as e:
            steps.append(Step(target, str(value), "skip", str(e), explicit))
            continue
        action = "already" if current(settings, target) == value else "set"
        steps.append(Step(target, value, action, "", explicit))
    return steps


def values_of(settings):
    """Every target's current value except the mode itself: what a record keeps for one mode."""
    return {target: str(entry["currentValue"]) for target, entry in _index(settings).items()
            if target != "pictureMode" and entry.get("currentValue") is not None}


def merge_record(record, label, snapshot):
    """A copy of `record` with what `snapshot` adds; the first value recorded for any key wins.

    snapshot keys, all optional: taken, host, powerSaving, originalMode, and mode with values ({target: value}
    read in that mode before anything but the mode switch was written).
    """
    merged = copy.deepcopy(record) if record else {"version": RECORD_VERSION, "labels": {}}
    merged.setdefault("labels", {})
    taken = snapshot.get("taken")
    if taken:
        merged.setdefault("created", taken)
    if snapshot.get("host"):
        merged.setdefault("host", snapshot["host"])
    # One setting for the whole TV, so one value for every label: the one before this tool first changed it. Only a
    # run that turns it off passes it, so a run that found it off already cannot hide a later change.
    if snapshot.get("powerSaving") is not None:
        merged.setdefault("powerSaving", str(snapshot["powerSaving"]))
    entry = merged["labels"].setdefault(label, {})
    if taken:
        entry.setdefault("created", taken)
    if snapshot.get("originalMode"):
        entry.setdefault("originalMode", str(snapshot["originalMode"]))
    mode = snapshot.get("mode")
    if mode:
        values = entry.setdefault("modes", {}).setdefault(str(mode), {})
        for target, value in (snapshot.get("values") or {}).items():
            if target != "pictureMode":
                values.setdefault(target, str(value))
    return merged


def recorded_modes(entry):
    """The picture modes a label's record names: the one that was active, then each one it holds values for."""
    entry = entry or {}
    names = [str(entry["originalMode"])] if entry.get("originalMode") else []
    return names + [str(name) for name in (entry.get("modes") or {}) if name not in names]


def recorded_family(entry):
    """'dv' or 'sdr-hdr' for the content a label was recorded with, by the modes it names; None when it names none.

    Dolby Vision brings its own mode list, so a label that mixed the two could not be restored with either on screen.
    """
    names = recorded_modes(entry)
    if not names:
        return None
    return "dv" if all(name.lower().startswith("dv") for name in names) else "sdr-hdr"


def restore_plan(record, label):
    """The writes that put `label` back, as (target, value) in order: each recorded mode ("pictureMode") with its
    values, then the mode that was active, then "powerSaving". PlanError when the label was never recorded."""
    labels = (record or {}).get("labels") or {}
    if label not in labels:
        raise PlanError(f"no picture record for label {label!r}; recorded: {', '.join(labels) or 'none'}")
    entry = labels[label]
    steps = []
    for mode, values in (entry.get("modes") or {}).items():
        steps.append(("pictureMode", mode))
        # sorted() is stable: the TV's order, with RESTORE_LAST moved to the end.
        steps.extend(sorted(values.items(), key=lambda item: item[0] in RESTORE_LAST))
    if entry.get("originalMode"):
        steps.append(("pictureMode", entry["originalMode"]))
    if record.get("powerSaving") is not None:
        steps.append(("powerSaving", record["powerSaving"]))
    return steps


def _http_date(text):
    # Sony writes the old Netscape form, "Wed, 05-Nov-2026 10:00:00 GMT"; email.utils wants spaces.
    text = re.sub(r"(\d{1,2})-([A-Za-z]{3})-(\d{2,4})", r"\1 \2 \3", text.strip())
    try:
        parsed = email.utils.parsedate_to_datetime(text)
    except (TypeError, ValueError, IndexError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=datetime.timezone.utc)
    return parsed.timestamp()


def parse_set_cookie(headers, now=None, name="auth"):
    """(value, expiry in epoch seconds or None) of the `auth` cookie in a response's Set-Cookie headers, or None.

    `headers` is the response's header object (anything with get_all), a dict, or a list of Set-Cookie values or
    (name, value) pairs. Max-Age wins over Expires, as in RFC 6265.
    """
    now = time.time() if now is None else now
    if isinstance(headers, str):
        lines = [headers]
    elif hasattr(headers, "get_all"):
        lines = headers.get_all("Set-Cookie") or []
    elif isinstance(headers, dict):
        lines = []
        for key, value in headers.items():
            if key.lower() == "set-cookie":
                lines.extend(value if isinstance(value, (list, tuple)) else [value])
    else:
        lines = [item[1] if isinstance(item, tuple) else item for item in headers or []
                 if not isinstance(item, tuple) or str(item[0]).lower() == "set-cookie"]
    for line in lines:
        parts = str(line).split(";")
        key, _, value = parts[0].partition("=")
        value = value.strip().strip('"')
        if key.strip() != name or not value:
            continue
        max_age = expires = None
        for attribute in parts[1:]:
            attr, _, attr_value = attribute.partition("=")
            attr = attr.strip().lower()
            if attr == "max-age" and re.fullmatch(r"-?\d+", attr_value.strip()):
                max_age = int(attr_value.strip())
            elif attr == "expires":
                expires = _http_date(attr_value)
        return value, (now + max_age if max_age is not None else expires)
    return None


def family_text(family, settings):
    if family == "dv":
        return "Dolby Vision (only Dolby Vision picture modes are on offer)"
    if family == "sdr-hdr":
        return f"SDR or HDR10/HLG (the API does not say which; hdrMode is {current(settings, 'hdrMode') or '?'})"
    return "unknown: the TV reported no pictureMode"


def table(rows):
    widths = [max(len(str(row[i])) for row in rows) for i in range(len(rows[0]))]
    return ["  ".join(str(cell).ljust(width) for cell, width in zip(row, widths)).rstrip() for row in rows]


def outcome(before, after, mode, steps, power=None, tuned=None):
    """Rows of the before/after table and the list of what did not take.

    before is the read before anything was written; tuned, when apply switched modes, the read right after the
    switch (the tuned mode's own values, which the plan was made from). power: (before, after, written) for
    power saving, or None when it was not read.
    """
    before, after = _index(before), _index(after)
    tuned = _index(tuned) if tuned is not None else None
    by_target = {step.target: step for step in steps}
    original = current(before, "pictureMode") or "?"
    if tuned is None:
        rows = [("target", "before", "after", "")]
    else:
        rows = [("target", f"{original} before", f"{mode} before", f"{mode} after", "")]
    failures = []
    targets = list(after) + [t for t in before if t not in after] + [t for t in by_target
                                                                     if t not in after and t not in before]
    for target in targets:
        was = current(before, target) or "-"
        now = current(after, target) or "-"
        middle = () if tuned is None else (current(tuned, target) or "-",)
        note = ""
        step = by_target.get(target)
        if target == "pictureMode":
            if now != mode:
                note = f"DID NOT TAKE (wanted {mode})"
                failures.append(f"pictureMode (wanted {mode}, the TV says {now})")
            else:
                note = "already" if was == mode else "set"
        elif step and step.action in ("set", "already"):
            if now != step.value:
                note = f"DID NOT TAKE (wanted {step.value})"
                failures.append(f"{target} (wanted {step.value}, the TV says {now})")
            else:
                note = step.action
        elif step:
            note = f"{'NOT APPLIED' if step.explicit else 'skipped'}: {step.reason}"
            if step.explicit:
                failures.append(f"{target} (asked for {step.value}: {step.reason})")
        rows.append((target, was) + middle + (now, note))
    if power is not None:
        was, now, written = power
        if written:
            note = "set" if now == "off" else "DID NOT TAKE (wanted off)"
            if now != "off":
                failures.append(f"powerSaving (wanted off, the TV says {now})")
        else:
            note = "already" if was == "off" else ""
        rows.append(("powerSaving", was or "?") + (() if tuned is None else ("",)) + (now or "?", note))
    return rows, failures


# ---- files ---------------------------------------------------------------------------------------------------

def host_tag(host):
    return re.sub(r"[^A-Za-z0-9.-]", "_", host or "no-host")


def auth_path(host):
    return Path.home() / ".cleanyourtv" / f"bravia-{host_tag(host)}.json"


def record_path(host):
    return Path.home() / ".cleanyourtv" / f"bravia-picture-{host_tag(host)}.json"


def write_json(path, data, private=False):
    """Written next to its final name first, then moved over it: a crash halfway must not leave a truncated file
    (a truncated record would block every retry as "already recorded"). private files are 0600."""
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600 if private else 0o644)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        # A .tmp left by an earlier crash keeps its old mode through O_CREAT; set it again.
        if private and hasattr(os, "fchmod"):
            os.fchmod(f.fileno(), 0o600)
        json.dump(data, f, indent=2)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(str(tmp), str(path))


def write_record(path, record):
    write_json(path, record)


def load_record(path):
    """The picture record, None when there is none yet; a damaged one stops the run and stays where it is."""
    path = Path(path)
    if not path.exists():
        return None
    try:
        with open(path, encoding="utf-8") as f:
            record = json.load(f)
    except (OSError, ValueError) as e:
        raise SystemExit(f"{path}: not a usable picture record ({e}).\n"
                         "It is left where it is: move it aside deliberately, a retry must not replace the "
                         "state recorded before the first change.")
    if not isinstance(record, dict) or record.get("version") != RECORD_VERSION \
            or not isinstance(record.get("labels"), dict):
        raise SystemExit(f"{path}: unknown picture record format; move it aside deliberately.")
    return record


def load_auth(host):
    path = auth_path(host)
    try:
        with open(path, encoding="utf-8") as f:
            saved = json.load(f)
        return saved if isinstance(saved, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as e:
        sys.stderr.write(f"ignoring {path} ({e}); `bravia.py pair` writes a new one\n")
        return {}


def when(epoch):
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(epoch))


def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def auth_state(host, now=None):
    """The headers that authorise writes, and a line saying where they come from or why there are none."""
    now = time.time() if now is None else now
    psk = os.environ.get("BRAVIA_PSK", "").strip()
    if psk:
        if not PSK_RE.fullmatch(psk):
            raise SystemExit("BRAVIA_PSK holds characters an HTTP header cannot carry")
        return Auth({"X-Auth-PSK": psk}, "pre-shared key from BRAVIA_PSK", True)
    saved = load_auth(host)
    cookie, expires = saved.get("cookie"), saved.get("expires")
    if not isinstance(cookie, str) or not COOKIE_RE.fullmatch(cookie):
        return Auth({}, NOT_PAIRED, False)
    if isinstance(expires, (int, float)) and now >= expires:
        return Auth({}, f"the pairing ran out on {when(expires)}: run `bravia.py pair` again (or set BRAVIA_PSK)",
                    False)
    until = f"until {when(expires)}" if isinstance(expires, (int, float)) else "(the TV gave no expiry)"
    return Auth({"Cookie": f"auth={cookie}"}, f"paired, cookie valid {until}", True)


# ---- the TV --------------------------------------------------------------------------------------------------

def endpoint(host, service):
    return f"http://{host}/sony/{service}"


# The TV is on the local network: never send its requests, or the pairing cookie, through a proxy.
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http_post(url, body, headers, timeout=15.0):
    """(status, headers, payload) for one POST. HTTP errors are answers here; an unreachable TV is
    ConnectionError."""
    request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), method="POST",
                                     headers=dict(headers, **{"Content-Type": "application/json; charset=UTF-8"}))
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as e:
        try:
            payload = e.read()
        except OSError:
            payload = b""
        finally:
            e.close()
        return e.code, e.headers, payload
    except (urllib.error.URLError, OSError) as e:
        raise ConnectionError(f"cannot reach the TV at {url.split('/sony/')[0]}: {getattr(e, 'reason', e)}") \
            from None


def parse_answer(status, payload, where=""):
    """The `result` of a JSON-RPC answer; ApiError for an error, AuthError for HTTP 401/403."""
    try:
        answer = json.loads(payload.decode("utf-8")) if payload else {}
    except (UnicodeDecodeError, ValueError):
        answer = {}
    error = answer.get("error") if isinstance(answer, dict) else None
    code = message = None
    if isinstance(error, list) and error:
        code, message = error[0], (str(error[1]) if len(error) > 1 else "")
    if status in (401, 403) or code in (401, 403):
        raise AuthError(code if code in (401, 403) else status, message or f"HTTP {status}", where)
    if error is not None:
        raise ApiError(code if code is not None else "?", message or str(error), where)
    if status != 200:
        raise ApiError(status, f"HTTP {status}", where)
    if not isinstance(answer, dict):
        raise ApiError("?", "not a JSON-RPC answer", where)
    result = answer.get("result", answer.get("results"))
    return [] if result is None else result


def make_post(host, headers=None, http=None):
    """post(service, method, params, version) -> result, over HTTP to the TV at host."""
    http = http or http_post
    ids = itertools.count(1)

    def post(service, method, params, version="1.0"):
        body = {"method": method, "params": params, "id": next(ids), "version": version}
        status, _, payload = http(endpoint(host, service), body, dict(headers or {}))
        return parse_answer(status, payload, f"{service}.{method}")
    return post


def read_settings(post):
    # An empty target asks for every picture setting at once.
    return settings_of(post("video", "getPictureQualitySettings", [{"target": ""}], "1.0"))


def read_power_saving(post):
    """The power saving mode, or None (with a line on stderr) when the TV does not say."""
    try:
        result = post("system", "getPowerSavingMode", [], "1.0")
    except ApiError as e:
        sys.stderr.write(f"power saving could not be read ({e})\n")
        return None
    entry = result[0] if result and isinstance(result[0], dict) else {}
    return str(entry["mode"]) if entry.get("mode") is not None else None


def set_setting(post, target, value):
    # One setting per call, so an error names the setting that caused it.
    post("video", "setPictureQualitySettings", [{"settings": [{"target": target, "value": str(value)}]}], "1.0")


def set_power_saving(post, mode):
    post("system", "setPowerSavingMode", [{"mode": mode}], "1.0")


def wait_for_mode(post, mode, sleep=time.sleep, tries=8, pause=0.5):
    """The settings read once the TV reports `mode`, or None if it never does. Values written before the switch has
    landed would go into the old mode."""
    for attempt in range(tries):
        settings = read_settings(post)
        if current(settings, "pictureMode") == mode:
            return settings
        if attempt + 1 < tries:
            sleep(pause)
    return None


def _refused(done, label):
    if done:
        sys.stderr.write(f"the TV refused a write after {', '.join(done)} had been changed. The record holds the "
                         f"values from before: picture restore --label {label} puts them back once writes work.\n")
    else:
        sys.stderr.write("the TV refused the first write: nothing was changed.\n")


# ---- commands ------------------------------------------------------------------------------------------------

def picture_apply(post, host, auth, label=None, overrides=None, dry_run=False, sleep=time.sleep):
    before = read_settings(post)
    family = signal_family(before)
    if family is None:
        raise SystemExit("the TV reported no pictureMode: turn it on and put a picture on screen, then run this again")
    overrides = dict(overrides or {})
    # Every --set is checked against what the TV offers before anything is written.
    for target, value in list(overrides.items()):
        overrides[target] = validate(before, target, value)
    mode = overrides.pop("pictureMode", None) or tuned_mode(family, before["pictureMode"].get("candidate"))
    label = label or family
    original = current(before, "pictureMode")
    switching = original != mode
    path = record_path(host)
    earlier = load_record(path)
    recorded_as = recorded_family(((earlier or {}).get("labels") or {}).get(label))
    if recorded_as not in (None, family):
        kinds = {"dv": "Dolby Vision", "sdr-hdr": "SDR or HDR10/HLG"}
        raise SystemExit(f"label {label} was recorded with {kinds[recorded_as]} on screen, and this is "
                         f"{kinds[family]}. One label cannot hold both: its restore would need picture modes no "
                         "signal offers together. Nothing was changed; pass another --label.")
    power = read_power_saving(post)
    print(f"{host}: {family_text(family, before)}")
    print(f"picture mode {original + ' -> ' + mode if switching else mode + ' (already)'}, label {label}, "
          f"power saving {power or 'unknown, left alone'}")

    if dry_run:
        if switching:
            print(f"would switch pictureMode to {mode}, then read the TV again: what is adjustable changes with "
                  f"the mode, so this list is from {original} and may change")
        # Planned from the mode on screen now, and said so: after a switch the TV is read again.
        for step in plan(before, original, overrides):
            was = current(before, step.target) or "-"
            what = {"set": f"{was} -> {step.value}", "already": f"{was} (already)"}.get(
                step.action, f"skipped: {step.reason}")
            print(f"  {step.target:<20} {what}")
        if power not in (None, "off"):
            print(f"  {'powerSaving':<20} {power} -> off")
        print(f"would record the state before the first write in {record_path(host)}")
        print("dry run: nothing was sent to the TV.")
        return 0

    if not auth.ok:
        raise SystemExit(f"nothing was changed: {auth.note}")

    taken = now_iso()
    snapshot = {"taken": taken, "host": host, "originalMode": original}
    if power not in (None, "off"):
        snapshot["powerSaving"] = power
    if not switching:
        snapshot.update(mode=mode, values=values_of(before))
    record = merge_record(earlier, label, snapshot)
    write_record(path, record)
    if earlier:
        print(f"keeping the record started {earlier.get('created', '?')} ({path}); only what it lacked was added")
    else:
        print(f"recorded the state before this run in {path}")

    done = []

    def refused():
        if not done:
            # Nothing reached the TV: what this run added must not win over a later run's reading (the first value
            # recorded wins), so the record goes back to how it was.
            if earlier is None:
                try:
                    path.unlink()
                except FileNotFoundError:
                    pass
            else:
                write_record(path, earlier)
        _refused(done, label)
        if not done:
            sys.stderr.write(f"The record is as it was before this run ({path}).\n")

    settings = before
    if switching:
        try:
            set_setting(post, "pictureMode", mode)
        except AuthError:
            refused()
            raise
        except ApiError as e:
            try:
                now = current(read_settings(post), "pictureMode")
            except (ApiError, ConnectionError):
                now = None
            if now == original:
                refused()
                raise SystemExit(f"the TV did not switch to {mode} ({e}); it is still in {original}.")
            raise SystemExit(f"the TV answered the switch to {mode} with {e} and now reports {now or 'no mode'}; "
                             f"nothing else was changed. picture restore --label {label} puts the mode back.")
        done.append("pictureMode")
        settings = wait_for_mode(post, mode, sleep)
        if settings is None:
            raise SystemExit(f"the TV still reports pictureMode {current(read_settings(post), 'pictureMode')} after "
                             f"the switch to {mode}; nothing else was changed. "
                             f"picture restore --label {label} puts the mode back.")
        # The values of the mode being tuned, before anything but the switch touched them.
        record = merge_record(record, label, {"taken": taken, "mode": mode, "values": values_of(settings)})
        write_record(path, record)

    steps = plan(settings, mode, overrides)
    for step in steps:
        if step.action != "set":
            continue
        try:
            set_setting(post, step.target, step.value)
        except AuthError:
            refused()
            raise
        except ApiError as e:
            # 40004 and the like: carry on with the rest, the re-read below says what took.
            print(f"  {step.target}={step.value}: {e}")
        done.append(step.target)

    power_written = False
    if power not in (None, "off"):
        try:
            set_power_saving(post, "off")
            power_written = True
        except AuthError:
            refused()
            raise
        except ApiError as e:
            print(f"  powerSaving=off: {e}")
            power_written = True

    after = read_settings(post)
    power_after = read_power_saving(post) if power is not None else None
    rows, failures = outcome(before, after, mode, steps,
                             (power, power_after, power_written) if power is not None else None,
                             tuned=settings if switching else None)
    for line in table(rows):
        print(f"  {line}")
    if failures:
        sys.stderr.write("did not take: " + "; ".join(failures) + "\n"
                         f"The record still holds the values from before: picture restore --label {label} "
                         "puts them back.\n")
        return 1
    print(f"done. Undo with: bravia.py picture restore --label {label}")
    return 0


def picture_restore(post, host, auth, label=None, dry_run=False, sleep=time.sleep):
    path = record_path(host)
    record = load_record(path)
    if record is None:
        raise SystemExit(f"no picture record for {host} ({path}): nothing to restore.")
    labels = list(record.get("labels") or {})
    settings = None
    if label is None:
        try:
            settings = read_settings(post)
        except ConnectionError as e:
            raise SystemExit(f"{e}\nPass --label ({', '.join(labels)}) to see the plan without the TV.")
        label = signal_family(settings)
        if label is None:
            raise SystemExit("the TV reported no pictureMode: turn it on and put a picture on screen, or pass --label")
        if label not in labels:
            # Labels named at apply time (sdr, hdr10) are not guessed: the API cannot tell SDR from HDR10.
            fits = [name for name in labels if recorded_family(record["labels"][name]) == label]
            raise SystemExit(f"nothing recorded for what is on screen ({label}); recorded: {', '.join(labels)}.\n"
                             + (f"Recorded with this kind of content: {', '.join(fits)}; pass --label with the one "
                                "applied to what is on screen." if fits else
                                "Put that kind of content on screen, or pass --label."))
    steps = restore_plan(record, label)
    entry = record["labels"][label]
    print(f"label {label}, recorded {entry.get('created') or record.get('created') or '?'}, in this order:")
    for target, value in steps:
        print(f"  {target:<20} -> {value}")
    others = [name for name in labels if name != label]
    if others:
        print(f"also recorded, not touched now: {', '.join(others)} (each with that kind of content on screen, "
              "--label NAME)")
    # Labels are kept in the order they were first recorded. A later one that found this label's mode already tuned
    # recorded that tuning as its own "before", so restoring it after this one would bring the tuning back.
    later, shared = [], []
    for name in labels[labels.index(label) + 1:]:
        common = [mode for mode in entry.get("modes") or {} if mode in recorded_modes(record["labels"][name])]
        if common:
            later.append(name)
            shared += [mode for mode in common if mode not in shared]
    if later:
        print(f"note: {', '.join(later)}, recorded after {label}, also covers {', '.join(shared)}: undo in reverse "
              f"order, {later[-1]} first, unless that was done already.")
    if dry_run:
        print("dry run: nothing was sent to the TV.")
        return 0
    if not auth.ok:
        raise SystemExit(f"nothing was changed: {auth.note}")

    settings = settings or read_settings(post)
    offered = mode_names(settings)
    missing = []
    for target, value in steps:
        if target == "pictureMode" and value not in offered and value not in missing:
            missing.append(value)
    if missing:
        raise SystemExit(f"label {label} needs picture mode {', '.join(missing)}, which the signal on screen does not "
                         f"offer ({', '.join(offered) or 'none'}). Nothing was changed: put that kind of content on "
                         "screen first.")

    failures = []
    pending = []
    done = []
    mode = current(settings, "pictureMode")
    print("writing (values the TV already has are left alone):")

    def check(settings):
        # Values are checked in their own mode, before the switch away from it.
        if not pending:
            return settings
        settings = read_settings(post)
        for target, value in pending:
            if current(settings, target) != value:
                failures.append(f"{target} in {mode} (wanted {value}, the TV says {current(settings, target)})")
        del pending[:]
        return settings

    skipping = False
    try:
        for index, (target, value) in enumerate(steps):
            if target == "pictureMode":
                settings = check(settings)
                skipping = False
                if mode == value:
                    print(f"  pictureMode {value}: already")
                    continue
                try:
                    set_setting(post, "pictureMode", value)
                    done.append("pictureMode")
                    switched = wait_for_mode(post, value, sleep)
                    reason = "the TV still reports another mode"
                except AuthError:
                    raise
                except ApiError as e:
                    switched, reason = None, str(e)
                if switched is None:
                    # This mode's values stay unwritten, so none lands in another mode. The switch back and power
                    # saving do not depend on it and still run.
                    left = [name for name, _ in itertools.takewhile(
                        lambda step: step[0] not in ("pictureMode", "powerSaving"), steps[index + 1:])]
                    failures.append(f"pictureMode (wanted {value}: {reason})"
                                    + (f"; left alone so none lands in another mode: {', '.join(left)}"
                                       if left else ""))
                    print(f"  pictureMode -> {value}: did not switch ({reason})")
                    skipping, mode = True, None
                    continue
                settings, mode = switched, value
                print(f"  pictureMode -> {value}")
            elif target == "powerSaving":
                now = read_power_saving(post)
                if now == value:
                    print(f"  powerSaving {value}: already")
                    continue
                try:
                    set_power_saving(post, value)
                except AuthError:
                    raise
                except ApiError as e:
                    print(f"  powerSaving={value}: {e}")
                done.append("powerSaving")
                now = read_power_saving(post)
                if now != value:
                    failures.append(f"powerSaving (wanted {value}, the TV says {now})")
                else:
                    print(f"  powerSaving -> {value}")
            elif skipping:
                continue
            else:
                entry = settings.get(target)
                now = current(settings, target)
                if entry is None:
                    failures.append(f"{target} (recorded {value}; not on this TV now)")
                elif now == value:
                    continue
                elif not is_available(entry):
                    failures.append(f"{target} in {mode} (recorded {value}, now {now}, not adjustable there)")
                else:
                    try:
                        set_setting(post, target, value)
                    except AuthError:
                        raise
                    except ApiError as e:
                        print(f"  {target}={value}: {e}")
                    done.append(target)
                    pending.append((target, value))
                    print(f"  {target} {now} -> {value}")
        check(settings)
    except AuthError:
        _refused(done, label)
        raise
    if failures:
        sys.stderr.write("not put back: " + "; ".join(failures) + "\n")
        return 1
    print("restored.")
    return 0


def show_record(host):
    path = record_path(host)
    record = load_record(path)
    if record is None:
        sys.stderr.write(f"no picture record for {host} yet ({path})\n")
        return 1
    print(f"picture record for {record.get('host', host)}, started {record.get('created', '?')}: {path}")
    print(f"power saving before: {record.get('powerSaving', 'not recorded')}")
    for label, entry in record["labels"].items():
        print(f"label {label} ({entry.get('created', '?')}): picture mode was {entry.get('originalMode', '?')}")
        for mode, values in (entry.get("modes") or {}).items():
            print(f"  {mode}: " + ", ".join(f"{target}={value}" for target, value in values.items()))
    print("keep this file: it is the only copy of the state before the first change.")
    return 0


def status(post, host, auth, as_json=False):
    settings = read_settings(post)
    power = read_power_saving(post)
    family = signal_family(settings)
    if as_json:
        print(json.dumps({"host": host, "family": family, "pictureMode": current(settings, "pictureMode"),
                          "powerSaving": power, "settings": list(settings.values())}, indent=1))
        return 0
    print(f"TV {host}")
    print(f"power saving: {power or 'unknown'}")
    print(f"signal: {family_text(family, settings)}")
    print(f"picture mode: {current(settings, 'pictureMode') or '?'}")
    rows = [("target", "value", "adjustable", "candidates")]
    rows += [(target, current(settings, target) or "-", "yes" if is_available(entry) else "no",
              describe_choices(entry)) for target, entry in settings.items()]
    for line in table(rows):
        print(f"  {line}")
    print(f"writes: {auth.note}")
    try:
        record = load_record(record_path(host))
    except SystemExit as e:
        record = None
        print(f"record: {e}")
    if record:
        labels = [f"{name} ({', '.join(entry.get('modes') or {}) or 'mode only'})"
                  for name, entry in record["labels"].items()]
        print(f"record: {record_path(host)}, labels: {', '.join(labels)}")
    return 0


def get(post, target):
    settings = read_settings(post)
    entry = settings.get(target)
    if entry is None:
        raise SystemExit(f"{target!r} is not a picture setting on this TV; it has: {', '.join(settings) or 'none'}")
    mode = current(settings, "pictureMode") or "this mode"
    print(f"{target} = {current(settings, target) or '-'} ({'adjustable' if is_available(entry) else 'not adjustable'}"
          f" in {mode}; candidates: {describe_choices(entry)})")
    return 0


def call(post, service, method, params_text, version="1.0"):
    for kind, text, pattern in (("service", service, NAME_RE), ("method", method, NAME_RE),
                                ("version", version, VERSION_RE)):
        if not pattern.fullmatch(text):
            raise SystemExit(f"not a {kind} name: {text!r}")
    try:
        params = json.loads(params_text)
    except ValueError as e:
        raise SystemExit(f"params are not JSON ({e}); e.g. '[{{\"target\": \"\"}}]' or '[]'")
    if isinstance(params, dict):
        params = [params]
    if not isinstance(params, list):
        raise SystemExit("params must be a JSON list (or one object)")
    print(json.dumps(post(service, method, params, version), indent=1, ensure_ascii=False))
    return 0


def prompt_pin():
    """The PIN a person reads off the TV, typed at this terminal. Never from arguments, the environment or a file:
    typing it is what shows someone is at the TV."""
    if not sys.stdin.isatty():
        raise SystemExit("pair needs a person at a terminal to type the PIN the TV shows; run it in a terminal "
                         "window, not through a pipe.")
    for _ in range(3):
        try:
            pin = input("PIN shown on the TV: ").strip()
        except EOFError:
            break
        # An Indian or Japanese keyboard may type its own digits; the TV wants the ASCII ones.
        pin = "".join(str(int(ch)) if ch.isdecimal() else ch for ch in pin)
        if PIN_RE.fullmatch(pin):
            return pin
        print("the PIN is the 4 digits on the TV screen")
    raise SystemExit("no PIN entered; nothing was paired. Run pair again when someone is at the TV.")


def pair(host, http=None, read_pin=None, now=None):
    http = http or http_post
    read_pin = read_pin or prompt_pin
    path = auth_path(host)
    saved = load_auth(host)
    clientid = saved.get("clientid")
    if not isinstance(clientid, str) or not re.fullmatch(r"cleanyourtv:[0-9a-f-]{36}", clientid):
        clientid = f"cleanyourtv:{uuid.uuid4()}"
    nickname = f"CleanYourTV ({socket.gethostname().split('.')[0] or 'computer'})"
    saved.update(host=host, clientid=clientid, nickname=nickname)
    # The same client id has to go with the PIN, and with any later pairing: keep it before the TV sees it.
    write_json(path, saved, private=True)
    body = {"method": "actRegister", "id": 1, "version": "1.0",
            "params": [{"clientid": clientid, "nickname": nickname, "level": "private"},
                       [{"function": "WOL", "value": "yes"}]]}
    url = endpoint(host, "accessControl")
    status, headers, payload = http(url, body, {})
    # 401 is the expected answer: the TV puts a PIN on screen and wants it back with the same request.
    if status == 401:
        print(f"the TV shows a 4-digit PIN now (it lists this computer as {nickname!r}).")
        pin = read_pin()
        if not PIN_RE.fullmatch(str(pin)):
            raise SystemExit("the PIN is 4 digits; nothing was paired.")
        basic = base64.b64encode(f":{pin}".encode("ascii")).decode("ascii")
        status, headers, payload = http(url, dict(body, id=2), {"Authorization": f"Basic {basic}"})
        if status in (401, 403):
            raise SystemExit("the TV did not accept that PIN; nothing was paired. Run pair again, the TV shows a new "
                             "one each time.")
    elif status != 200:
        try:
            parse_answer(status, payload, "accessControl.actRegister")
            detail = f"HTTP {status}"
        except ApiError as e:
            detail = str(e)
        raise SystemExit(f"the TV refused the registration ({detail}). Under Network & Internet > Home network > IP "
                         "control, Authentication must allow Normal (PIN) pairing; or set BRAVIA_PSK instead.")
    parse_answer(status, payload, "accessControl.actRegister")
    cookie = parse_set_cookie(headers, now)
    if not cookie or not COOKIE_RE.fullmatch(cookie[0]):
        raise SystemExit("the TV accepted the registration but handed back no auth cookie; set BRAVIA_PSK instead.")
    saved.update(cookie=cookie[0], expires=cookie[1], paired=now_iso())
    write_json(path, saved, private=True)
    until = f"until {when(cookie[1])}" if cookie[1] else "(the TV gave no expiry)"
    print(f"paired: the cookie is valid {until}, kept in {path} (mode 0600). Run pair again after that.")
    return 0


def parse_args(argv):
    parser = argparse.ArgumentParser(prog="bravia.py", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", metavar="command")
    commands.add_parser("pair", help="one-time PIN pairing, with someone at the TV")
    p = commands.add_parser("status", help="power saving, signal, every picture target")
    p.add_argument("--json", action="store_true")
    p = commands.add_parser("get", help="one picture target")
    p.add_argument("target")
    p = commands.add_parser("call", help="any method, as is")
    p.add_argument("service")
    p.add_argument("method")
    p.add_argument("params", help="JSON list, e.g. '[]' or '[{\"target\": \"\"}]'")
    p.add_argument("version", nargs="?", default="1.0")
    picture = commands.add_parser("picture", help="apply | restore | show-record")
    actions = picture.add_subparsers(dest="action", metavar="action")
    p = actions.add_parser("apply")
    p.add_argument("--label")
    p.add_argument("--set", action="append", default=[], metavar="TARGET=VALUE")
    p.add_argument("--dry-run", action="store_true")
    p = actions.add_parser("restore")
    p.add_argument("--label")
    p.add_argument("--dry-run", action="store_true")
    actions.add_parser("show-record")
    args = parser.parse_args(argv)
    if not args.command or (args.command == "picture" and not args.action):
        parser.print_usage(sys.stderr)
        sys.stderr.write(__doc__.split("\n\n")[1] + "\n")
        raise SystemExit(2)
    if getattr(args, "label", None) is not None and not LABEL_RE.fullmatch(args.label):
        parser.error(f"--label takes letters, digits, '.', '_' or '-' (up to 32): {args.label!r}")
    overrides = {}
    for item in getattr(args, "set", None) or []:
        target, sep, value = item.partition("=")
        if not sep or not target.strip() or not value.strip():
            parser.error(f"--set takes target=value, e.g. --set contrast=85: {item!r}")
        overrides[target.strip()] = value.strip()
    args.overrides = overrides
    return args


def main(argv=None, http=None, read_pin=None, sleep=time.sleep, post=None):
    """http, post, read_pin and sleep stand in for the network, the person at the TV and the clock in tests."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except (AttributeError, ValueError):
            pass
    args = parse_args(sys.argv[1:] if argv is None else argv)
    host = os.environ.get("TV_HOST", "").strip()
    if not host:
        raise SystemExit("Set TV_HOST to your TV's IP address, e.g.  TV_HOST=192.168.1.50 tools/bravia.py status\n"
                         "(find it under Settings → Network, or look for the TV in `arp -a`)")
    if not HOST_RE.fullmatch(host):
        raise SystemExit(f"TV_HOST should be the TV's IP address or host name, not {host!r}")
    try:
        if args.command == "pair":
            return pair(host, http=http, read_pin=read_pin)
        if args.command == "picture" and args.action == "show-record":
            return show_record(host)
        auth = auth_state(host)
        post = post or make_post(host, auth.headers, http)
        if args.command == "status":
            return status(post, host, auth, as_json=args.json)
        if args.command == "get":
            return get(post, args.target)
        if args.command == "call":
            return call(post, args.service, args.method, args.params, args.version)
        if args.action == "apply":
            return picture_apply(post, host, auth, label=args.label, overrides=args.overrides,
                                 dry_run=args.dry_run, sleep=sleep)
        return picture_restore(post, host, auth, label=args.label, dry_run=args.dry_run, sleep=sleep)
    except PlanError as e:
        sys.stderr.write(f"{e}\n")
        return 1
    except AuthError as e:
        sys.stderr.write(f"{e}: the TV wants authorisation for this. {NOT_PAIRED.split(': ', 1)[1]} "
                         "(a pairing runs out after about two weeks).\n")
        return 1
    except ApiError as e:
        sys.stderr.write(f"{e}\n")
        return 1
    except ConnectionError as e:
        sys.stderr.write(f"{e}\nIs the TV on, on this network, with IP control enabled?\n")
        return 1


if __name__ == "__main__":
    sys.exit(main())
