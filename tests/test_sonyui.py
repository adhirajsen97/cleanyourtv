"""tools/sonyui.py: reading Sony's Picture panel from real dumps, and walking a simulated one."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import sonyui  # noqa: E402

FIXTURES = ROOT / "tests" / "fixtures" / "sonyui"


def screen(name):
    return sonyui.parse_screen((FIXTURES / name).read_text(encoding="utf-8"))


class ParseRealDumps(unittest.TestCase):
    """Dumps taken from the XR-83A80L (Google TV 12) while walking its Picture panel."""

    def test_picker_row_with_its_arrow(self):
        s = screen("vivid-000.xml")
        self.assertEqual(s["package"], sonyui.SETTINGS_PACKAGE)
        self.assertEqual(s["title"], "Picture")
        r = sonyui.focused_row(s)
        self.assertEqual((r["title"], r["focus"]), ("Picture mode", "row"))
        c = sonyui.focused_control(r)
        self.assertEqual((c["kind"], c["value"], c["left"], c["right"]), ("picker", "Vivid", False, True))
        self.assertEqual(sonyui.active_mode(s), "Vivid")

    def test_row_with_sub_options(self):
        r = sonyui.focused_row(screen("vivid-004.xml"))
        self.assertEqual((r["title"], r["focus"], r["control"]["value"]), ("Ambient light sensor", "row", "On"))
        self.assertEqual([x["title"] for x in r["subs"]],
                         ["Auto luminance level", "Auto tone curve", "Auto white balance"])
        self.assertFalse(r["own_in_subs"])

    def test_focus_on_a_sub_option(self):
        r = sonyui.focused_row(screen("vivid-005.xml"))
        self.assertEqual(r["focus"], "Auto luminance level")
        c = sonyui.focused_control(r)
        self.assertEqual((c["value"], c["left"], c["right"]), ("Off", True, False))

    def test_collapsed_and_open_headers(self):
        s = screen("vivid-009.xml")
        r = sonyui.focused_row(s)
        self.assertTrue(r["header"])
        self.assertEqual(r["title"], "Brightness")
        self.assertTrue(sonyui.header_collapsed(s, r))
        s = screen("vivid-010.xml")
        r = sonyui.focused_row(s)
        self.assertFalse(sonyui.header_collapsed(s, r))

    def test_slider_row(self):
        r = sonyui.focused_row(screen("vivid-011.xml"))
        c = sonyui.focused_control(r)
        self.assertEqual((r["title"], c["kind"], c["value"]), ("Brightness", "slider", "Max"))

    def test_own_picker_listed_as_first_sub_option(self):
        r = sonyui.focused_row(screen("vivid-035.xml"))
        self.assertTrue(r["own_in_subs"])
        self.assertEqual((r["focus"], r["control"]["value"]), ("row", "Auto"))
        self.assertEqual([x["title"] for x in r["subs"]], ["Smoothness", "Clearness"])
        r = sonyui.focused_row(screen("vivid-036.xml"))
        self.assertEqual(r["focus"], "Smoothness")
        self.assertEqual(sonyui.focused_control(r)["kind"], "slider")
        r = sonyui.focused_row(screen("vivid-029.xml"))
        self.assertEqual((r["title"], r["focus"]), ("Reality Creation", "Resolution"))

    def test_greyed_row(self):
        r = sonyui.focused_row(screen("vivid-042.xml"))
        self.assertEqual(r["title"], "HDMI video range")
        self.assertFalse(r["control"]["left"] or r["control"]["right"])

    def test_netflix_panel_has_a_basic_header_and_its_switch(self):
        s = screen("netflix-calibrated.xml")
        rows = [(r["title"], r["header"]) for r in s["rows"]]
        self.assertEqual(rows[0], ("Basic", True))
        r = sonyui.focused_row(s)
        self.assertEqual((r["title"], r["enabled"]), ("Picture mode", False))
        self.assertEqual(sonyui.active_mode(s), "Calibrated")
        switch = next(x for x in s["rows"] if x["title"] == "Netflix calibrated mode")
        self.assertEqual(switch["control"]["value"], "On")

    def test_calibrated_mode_is_read_from_the_reset_row(self):
        s = screen("prime-calibrated.xml")
        self.assertEqual(sonyui.active_mode(s), "Calibrated")
        titles = [r["title"] for r in s["rows"]]
        self.assertIn("Prime Video calibrated mode", titles)


class Pure(unittest.TestCase):
    def test_norm(self):
        self.assertEqual(sonyui.norm("R‑Gain"), sonyui.norm("r-gain"))
        self.assertEqual(sonyui.norm("  Peak   luminance "), "peak luminance")

    def test_parse_target(self):
        self.assertEqual(sonyui.parse_target("Peak luminance=High"), (None, "Peak luminance", None, "High"))
        self.assertEqual(sonyui.parse_target("Brightness/Gamma=0"), ("Brightness", "Gamma", None, "0"))
        self.assertEqual(sonyui.parse_target("Motion/Motionflow/Smoothness=1"),
                         ("Motion", "Motionflow", "Smoothness", "1"))
        self.assertEqual(sonyui.parse_target("Video signal/Colour space=sRgb/bt709")[3], "sRgb/bt709")
        for bad in ("Gamma", "=1", "Gamma=", "a/b/c/d=1"):
            with self.assertRaises(ValueError):
                sonyui.parse_target(bad)

    def test_slider_math(self):
        self.assertEqual(sonyui.slider_number("Max"), "max")
        self.assertEqual(sonyui.slider_number("-2"), -2)
        self.assertEqual(sonyui.slider_keys("-2", "0"), ("RIGHT", 2))
        self.assertEqual(sonyui.slider_keys("75", "50"), ("LEFT", 25))
        self.assertEqual(sonyui.slider_keys("40", "Max"), ("RIGHT", 1))
        self.assertEqual(sonyui.slider_keys("Max", "90"), ("LEFT", 1))
        self.assertEqual(sonyui.slider_keys("Min", "1"), ("RIGHT", 1))
        self.assertEqual(sonyui.slider_keys("3", "3"), ("", 0))
        with self.assertRaises(ValueError):
            sonyui.slider_keys("High", "1")

    def test_record_first_value_wins_and_restore_order(self):
        rec = {"version": 1, "labels": {}}
        self.assertTrue(sonyui.remember(rec, "video-sdr", "Calibrated", "Basic/Picture mode", "Calibrated"))
        self.assertTrue(sonyui.remember(rec, "video-sdr", "Custom", "Brightness/Gamma", "-2"))
        self.assertFalse(sonyui.remember(rec, "video-sdr", "Custom", "Brightness/Gamma", "0"))
        self.assertTrue(sonyui.remember(rec, "video-sdr", "Custom", "Brightness/Peak luminance", "Medium"))
        self.assertTrue(sonyui.remember(rec, "video-sdr", "Vivid", "Clarity/Sharpness", "75"))
        self.assertFalse(sonyui.remember(rec, "video-sdr", "custom", "brightness/gamma", "1"))   # same row, other spelling
        sonyui.remember(rec, "video-sdr", "Calibrated", "Basic/Prime Video calibrated mode", "On")
        passes, finish, switches = sonyui.restore_passes(rec, "video-sdr")
        self.assertEqual(passes, [("Custom", [("Brightness/Gamma", "-2"), ("Brightness/Peak luminance", "Medium")]),
                                  ("Vivid", [("Clarity/Sharpness", "75")])])
        self.assertEqual(finish, "Calibrated")
        self.assertEqual(switches, [("Basic/Prime Video calibrated mode", "On")])
        self.assertEqual(sonyui.restore_passes(rec, "nothing"), ([], None, []))

    def test_labels_belong_to_one_app_and_signal(self):
        self.assertEqual(sonyui.default_label("com.netflix.ninja", True), "com.netflix.ninja-dv")
        self.assertEqual(sonyui.default_label(None, False), "unknown-sdr")
        self.assertTrue(sonyui.LABEL_RE.fullmatch("com.amazon.amazonvideo.livingroom-sdr"))
        self.assertFalse(sonyui.LABEL_RE.fullmatch("--dry-run"))
        rec = {"version": 1, "labels": {}}
        self.assertIsNone(sonyui.check_label(rec, "x", "com.netflix.ninja", False))
        sonyui.label_context(rec, "x", "com.netflix.ninja", False)
        self.assertIsNone(sonyui.check_label(rec, "x", "com.netflix.ninja", False))
        self.assertIn("recorded over com.netflix.ninja", sonyui.check_label(rec, "x", "com.gathin.tvhome", False))
        self.assertIn("Dolby Vision", sonyui.check_label(rec, "x", "com.netflix.ninja", True))

    def test_command_line_options(self):
        for argv in (["set", "--label", "--dry-run", "Gamma=0"], ["set", "--label"], ["set", "--bogus", "Gamma=0"],
                     ["set", "Gamma"], ["set"], ["restore", "--label"]):
            with self.assertRaises(SystemExit, msg=argv):
                sonyui.main(argv)
        self.assertEqual(sonyui.main(["set", "--dry-run", "Video signal/Colour space=sRgb/bt709"]), 0)

    def test_restore_needs_a_label(self):
        with tempfile.TemporaryDirectory() as d:
            old = os.environ.get("SONYUI_RECORD")
            os.environ["SONYUI_RECORD"] = str(Path(d) / "rec.json")
            try:
                rec = {"version": 1, "labels": {}}
                sonyui.remember(rec, "a-sdr", "Custom", "Brightness/Gamma", "-2")
                sonyui.write_record(Path(d) / "rec.json", rec)
                with self.assertRaises(SystemExit) as cm:
                    sonyui.main(["restore"])
                self.assertIn("a-sdr", str(cm.exception))
                with self.assertRaises(SystemExit):
                    sonyui.main(["restore", "--label", "b-sdr"])
                self.assertEqual(sonyui.main(["restore", "--label", "a-sdr", "--dry-run"]), 0)
            finally:
                if old is None:
                    del os.environ["SONYUI_RECORD"]
                else:
                    os.environ["SONYUI_RECORD"] = old

    def test_label_context_first_run_wins(self):
        rec = {"version": 1, "labels": {}}
        sonyui.label_context(rec, "netflix", "com.netflix.ninja", False)
        sonyui.label_context(rec, "netflix", "com.gathin.tvhome", True)
        self.assertEqual((rec["labels"]["netflix"]["app"], rec["labels"]["netflix"]["dolby_vision"]),
                         ("com.netflix.ninja", False))

    def test_foreground_app(self):
        class Dev:
            def shell(self, command, read_timeout_s=None):
                return "    mResumedActivity: ActivityRecord{17085f6 u0 com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity t18107}\n"
        self.assertEqual(sonyui.foreground_app(Dev()), "com.amazon.amazonvideo.livingroom")

    def test_record_file_round_trip(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "sub" / "rec.json"
            rec = sonyui.load_record(path)
            sonyui.remember(rec, "home-sdr", "Vivid", "Basic/Picture mode", "Vivid", when="t")
            sonyui.write_record(path, rec)
            self.assertEqual(sonyui.load_record(path), rec)
            self.assertFalse(path.with_name(path.name + ".tmp").exists())
            path.write_text(json.dumps({"version": 9, "labels": {}}))
            with self.assertRaises(SystemExit):
                sonyui.load_record(path)

    def test_record_path_override(self):
        old = os.environ.get("SONYUI_RECORD")
        try:
            os.environ["SONYUI_RECORD"] = "/tmp/x.json"
            self.assertEqual(sonyui.record_path("1.2.3.4"), Path("/tmp/x.json"))
            del os.environ["SONYUI_RECORD"]
            self.assertTrue(str(sonyui.record_path("1.2.3.4")).endswith("sonyui-1.2.3.4.json"))
        finally:
            if old is not None:
                os.environ["SONYUI_RECORD"] = old


# ---- a simulated Picture panel ------------------------------------------------------------------------------

class Picker:
    def __init__(self, title, options, value, enabled=True):
        self.title, self.options, self.i, self.enabled = title, options, options.index(value), enabled

    @property
    def value(self):
        return self.options[self.i]

    def press(self, d):
        if self.enabled:
            self.i = max(0, min(len(self.options) - 1, self.i + d))


class Slider:
    def __init__(self, title, lo, hi, value):
        self.title, self.lo, self.hi, self.v, self.enabled = title, lo, hi, value, True

    @property
    def value(self):
        return "Max" if self.v == self.hi else "Min" if self.v == self.lo else str(self.v)

    def press(self, d):
        self.v = max(self.lo, min(self.hi, self.v + d))


class Row:
    def __init__(self, control, subs=(), own_in_subs=False, title=None):
        self.control, self.subs, self.own_in_subs = control, list(subs), own_in_subs
        self._title = title
        self.enabled = control.enabled if control else True

    @property
    def title(self):
        if callable(self._title):
            return self._title()
        return self._title or self.control.title


class Header:
    def __init__(self, title, rows):
        self.title, self.rows, self.collapsed = title, rows, True


class FakeTV:
    """Enough of Sony's list behaviour for the walker: Down enters sub-options, Center folds headers."""

    def __init__(self, items, calibrated=False):
        self.items = items
        self.calibrated = calibrated    # an app's calibrated mode is in use; the picker shows a stand-in
        self.focus = 0           # index into visible()
        self.sub = None          # index into the focused row's sub-options
        self.keys = []
        self.drop_next = 0       # drop this many key presses (a busy TV)

    def visible(self):
        out = []
        for it in self.items:
            out.append(it)
            if isinstance(it, Header) and not it.collapsed:
                out.extend(it.rows)
        return out

    def subs_of(self, row):
        return ([row.control] if row.own_in_subs else []) + row.subs if isinstance(row, Row) else []

    def shell(self, command, read_timeout_s=None):
        if command.startswith("am start"):
            self.focus, self.sub = 0, None
            for it in self.items:
                if isinstance(it, Header):
                    it.collapsed = True
            return ""
        if command.startswith("input keyevent "):
            key = command.split("KEYCODE_")[1]
            self.keys.append(key)
            if self.drop_next:
                self.drop_next -= 1
                return ""
            self.press(key)
            return ""
        if command.startswith("uiautomator dump"):
            return ""
        if command.startswith("cat "):
            return self.render()
        raise AssertionError(command)

    def press(self, key):
        vis = self.visible()
        cur = vis[self.focus]
        subs = self.subs_of(cur)
        if key == "DPAD_DOWN":
            if subs and self.sub is None and not cur.own_in_subs:
                self.sub = 0
            elif subs and self.sub is not None and self.sub + 1 < len(subs):
                self.sub += 1
            elif subs and cur.own_in_subs and self.sub is None and len(subs) > 1:
                self.sub = 1
            elif self.focus + 1 < len(vis):
                self.focus, self.sub = self.focus + 1, None
        elif key == "DPAD_UP":
            if self.sub:
                self.sub -= 1
            elif self.sub == 0 and not cur.own_in_subs:
                self.sub = None
            elif self.focus > 0:
                self.focus, self.sub = self.focus - 1, None
        elif key == "DPAD_CENTER":
            if isinstance(cur, Header):
                cur.collapsed = not cur.collapsed
        elif key in ("DPAD_LEFT", "DPAD_RIGHT"):
            d = -1 if key == "DPAD_LEFT" else 1
            target = self.focused_control()
            if target is not None and target.enabled:
                target.press(d)
                if target.title == "Picture mode":
                    self.calibrated = False
        elif key == "BACK":
            self.backs = getattr(self, "backs", 0) + 1

    def focused_control(self):
        cur = self.visible()[self.focus]
        if not isinstance(cur, Row):
            return None
        subs = self.subs_of(cur)
        if self.sub is None:
            return cur.control
        return subs[self.sub]

    @staticmethod
    def node(attrs, children=""):
        a = " ".join(f"{k}={quoteattr(str(v))}" for k, v in attrs.items())
        return f"<node {a}>{children}</node>"

    def control_xml(self, c, focused, own):
        rid = "com.sony.dtv.configsettings:id/"
        if isinstance(c, Slider):
            value_id = "expandCurrentValue" if (own and focused) else "currentValue"
            bar = "expandedSeekBar" if (own and focused) else ("seekBar" if not own else "normalSeekBar")
            out = self.node({"resource-id": rid + value_id, "text": c.value})
            out += self.node({"resource-id": rid + bar, "text": ""})
            if focused:
                out += self.node({"resource-id": rid + "defaultIndicator", "text": ""})
            return out
        if isinstance(c, Picker):
            out = self.node({"resource-id": rid + "text", "text": c.value})
            if focused and c.enabled:
                prefix = "expandedArrow" if own else "arrow"
                if c.i > 0:
                    out += self.node({"resource-id": rid + prefix + "Left", "text": ""})
                if c.i < len(c.options) - 1:
                    out += self.node({"resource-id": rid + prefix + "Right", "text": ""})
            return out
        return ""

    def render(self):
        rid = "com.sony.dtv.configsettings:id/"
        rows = []
        for i, it in enumerate(self.visible()):
            focused = i == self.focus
            if isinstance(it, Header):
                inner = self.node({"resource-id": rid + "collapse_indicator", "text": ""})
                inner += self.node({"resource-id": rid + "title", "text": it.title})
                rows.append(self.node({"class": "android.widget.LinearLayout", "focusable": "true",
                                       "focused": str(focused).lower(), "text": ""},
                                      self.node({"resource-id": rid + "normalContainer", "text": ""}, inner)))
                continue
            if focused:
                own_focused = self.sub is None
                contents = self.node({"resource-id": rid + "expandedTitle", "text": it.title,
                                      "enabled": str(it.enabled).lower()})
                if it.control is not None and not it.own_in_subs:
                    contents += self.control_xml(it.control, own_focused, own=True)
                body = self.node({"resource-id": rid + "contentsContainer", "text": ""}, contents)
                subs = self.subs_of(it)
                if subs:
                    items = ""
                    for j, sub in enumerate(subs):
                        sub_focused = (self.sub == j) or (it.own_in_subs and j == 0 and self.sub is None)
                        items += self.node({"text": ""},
                                           self.node({"resource-id": rid + "title", "text": sub.title})
                                           + self.node({"resource-id": rid + "expandedRecyclerViewContainer", "text": ""},
                                                       self.control_xml(sub, sub_focused, own=False)))
                    body += self.node({"resource-id": rid + "panelListContainer", "text": ""},
                                      self.node({"resource-id": rid + "panelItemList", "text": ""}, items))
                inner = self.node({"resource-id": rid + "expandedContainer", "text": ""}, body)
            else:
                inner = self.node({"resource-id": rid + "normalContainer", "text": ""},
                                  self.node({"resource-id": rid + "title", "text": it.title,
                                             "enabled": str(it.enabled).lower()})
                                  + (self.control_xml(it.control, False, own=True) if it.control else ""))
            rows.append(self.node({"class": "android.widget.FrameLayout", "focusable": "true",
                                   "focused": str(focused).lower(), "text": "",
                                   "enabled": str(it.enabled).lower()}, inner))
        lst = self.node({"resource-id": rid + "list", "text": ""}, "".join(rows))
        title = self.node({"resource-id": rid + "decor_title", "text": "Picture"})
        return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
                + self.node({"package": sonyui.SETTINGS_PACKAGE, "text": ""}, title + lst) + "</hierarchy>")


def picture_menu(mode="Vivid", tv_state=None, mode_enabled=True):
    modes = ["Vivid", "Standard", "Cinema", "Game", "Graphics", "Photo", "Custom"]
    picture = Row(Picker("Picture mode", modes, mode, enabled=mode_enabled))
    state = tv_state if tv_state is not None else {}
    reset = Row(None, title=lambda: "Reset picture settings for "
                + ("Calibrated" if state.get("tv") and state["tv"].calibrated else picture.control.value))
    sensor = Row(Picker("Ambient light sensor", ["On", "Off"], "On"),
                 subs=[Picker("Auto luminance level", ["Off", "On"], "On"),
                       Picker("Auto tone curve", ["Off", "On"], "On")])
    brightness = Header("Brightness", [Row(Slider("Brightness", 0, 50, 50)),
                                       Row(Slider("Gamma", -3, 3, -2)),
                                       Row(Picker("Peak luminance", ["High", "Medium", "Low", "Off"], "Medium")),
                                       Row(Picker("HDR tone mapping", ["Gradation preferred"], "Gradation preferred",
                                                  enabled=False))])
    motion = Header("Motion", [Row(Picker("Motionflow", ["Auto", "Custom", "Off"], "Custom"),
                                   subs=[Slider("Smoothness", 0, 3, 0), Picker("Clearness", ["Low", "High"], "Low")],
                                   own_in_subs=True),
                               Row(Picker("Film mode", ["High", "Low", "Off"], "High"))])
    return [picture, reset, Header("Ambient Optimisation Pro", [sensor]), brightness, motion]


class Walk(unittest.TestCase):
    def setUp(self):
        self._sleep = sonyui.time.sleep
        sonyui.time.sleep = lambda s: None

    def tearDown(self):
        sonyui.time.sleep = self._sleep

    def run_walk(self, tv, specs, read_only=False):
        panel = sonyui.Panel(tv, log=lambda *a: None, settle=0)
        s = panel.open("picture")
        changes, rows = [], []
        targets = [sonyui.parse_target(x) for x in specs]
        missing = panel.walk(s, targets, on_change=lambda *a: changes.append(a), read_only=read_only,
                             sink=lambda section, r, scr: rows.append((section, r["title"])))
        return missing, changes, rows

    def find(self, items, title):
        for it in items:
            for row in ([it] if isinstance(it, Row) else it.rows if isinstance(it, Header) else []):
                if row.title == title:
                    return row.control
                for sub in row.subs:
                    if sub.title == title:
                        return sub
        raise KeyError(title)

    def test_profile_in_one_pass(self):
        items = picture_menu()
        tv = FakeTV(items)
        missing, changes, _ = self.run_walk(tv, [
            "Brightness/Peak luminance=High", "Basic/Picture mode=Custom",
            "Ambient Optimisation Pro/Ambient light sensor=Off", "Brightness/Gamma=0",
            "Motion/Motionflow/Smoothness=1", "Motion/Motionflow/Clearness=Low"])
        self.assertEqual(missing, [])
        self.assertEqual(self.find(items, "Picture mode").value, "Custom")
        self.assertEqual(self.find(items, "Ambient light sensor").value, "Off")
        self.assertEqual(self.find(items, "Gamma").value, "0")
        self.assertEqual(self.find(items, "Peak luminance").value, "High")
        self.assertEqual(self.find(items, "Smoothness").value, "1")
        # The picture mode change is recorded against the mode that was active, every other row against Custom.
        self.assertEqual(changes[0][:2], ("Basic", "Picture mode"))
        self.assertEqual(changes[0][3:], ("Vivid", "Custom", "Vivid"))
        self.assertTrue(all(c[5] == "Custom" for c in changes[1:]))
        # Clearness was already Low: no change reported.
        self.assertNotIn("Clearness", [c[2] for c in changes])

    def test_center_only_on_headers(self):
        items = picture_menu()
        tv = FakeTV(items)
        self.run_walk(tv, ["Motion/Film mode=Low"])
        # Two collapsed headers on the way that hold no target stay folded; Motion is opened once.
        self.assertEqual(tv.keys.count("DPAD_CENTER"), 1)
        self.assertEqual(self.find(items, "Film mode").value, "Low")

    def test_slider_to_max_and_back_through_the_word(self):
        items = picture_menu()
        self.find(items, "Brightness").v = 31
        tv = FakeTV(items)
        self.run_walk(tv, ["Brightness/Brightness=Max"])
        self.assertEqual(self.find(items, "Brightness").v, 50)
        self.run_walk(tv, ["Brightness/Brightness=49"])
        self.assertEqual(self.find(items, "Brightness").v, 49)
        self.run_walk(tv, ["Brightness/Brightness=50"])
        self.assertEqual(self.find(items, "Brightness").v, 50)

    def test_value_not_offered_is_put_back(self):
        items = picture_menu()
        tv = FakeTV(items)
        with self.assertRaises(SystemExit):
            self.run_walk(tv, ["Brightness/Peak luminance=Maximum"])
        self.assertEqual(self.find(items, "Peak luminance").value, "Medium")

    def test_unreachable_mode_stops_the_pass(self):
        items = picture_menu(mode="Custom")
        tv = FakeTV(items)
        with self.assertRaises(sonyui.ModeNotReached):
            self.run_walk(tv, ["Basic/Picture mode=Calibrated", "Brightness/Gamma=-1"])
        self.assertEqual(self.find(items, "Gamma").value, "-2")
        self.assertNotIn("DPAD_LEFT", tv.keys)
        self.assertNotIn("DPAD_RIGHT", tv.keys)

    def test_greyed_mode_stops_the_pass(self):
        state = {}
        items = picture_menu(mode="Vivid", tv_state=state, mode_enabled=False)
        tv = FakeTV(items, calibrated=True)
        state["tv"] = tv
        with self.assertRaises(sonyui.ModeNotReached):
            self.run_walk(tv, ["Basic/Picture mode=Custom", "Brightness/Gamma=0"])
        self.assertEqual(self.find(items, "Gamma").value, "-2")

    def test_stand_in_picker_value_is_really_selected(self):
        # Calibrated in use, the picker shows its first entry (Vivid): asking for Vivid must leave Calibrated.
        state = {}
        items = picture_menu(mode="Vivid", tv_state=state)
        tv = FakeTV(items, calibrated=True)
        state["tv"] = tv
        befores = []
        panel = sonyui.Panel(tv, log=lambda *a: None, settle=0)
        s = panel.open("picture")
        self.assertEqual(sonyui.active_mode(s), "Calibrated")
        panel.walk(s, [sonyui.parse_target("Basic/Picture mode=Vivid")],
                   before_change=lambda *a: befores.append(a))
        self.assertFalse(tv.calibrated)
        self.assertEqual(self.find(items, "Picture mode").value, "Vivid")
        self.assertEqual(befores, [("Basic", "Picture mode", None, "Calibrated", "Calibrated")])

    def test_old_value_is_recorded_before_the_first_key(self):
        items = picture_menu()
        tv = FakeTV(items)
        befores = []
        panel = sonyui.Panel(tv, log=lambda *a: None, settle=0)
        s = panel.open("picture")
        with self.assertRaises(SystemExit):
            panel.walk(s, [sonyui.parse_target("Brightness/Gamma=-7")], before_change=lambda *a: befores.append(a))
        self.assertEqual(befores, [("Brightness", "Gamma", None, "-2", "Vivid")])
        # Bounded: an out-of-range target gives up after a handful of presses.
        self.assertLess(tv.keys.count("DPAD_LEFT"), 12)

    def test_sub_option_is_recorded_with_the_tv_spelling(self):
        items = picture_menu()
        tv = FakeTV(items)
        befores = []
        panel = sonyui.Panel(tv, log=lambda *a: None, settle=0)
        s = panel.open("picture")
        panel.walk(s, [sonyui.parse_target("motion/motionflow/smoothness=2")],
                   before_change=lambda *a: befores.append(a))
        self.assertEqual(befores, [("Motion", "Motionflow", "Smoothness", "Min", "Vivid")])
        self.assertEqual(self.find(items, "Smoothness").value, "2")

    def test_slider_to_both_ends_by_number(self):
        items = picture_menu()
        tv = FakeTV(items)
        self.run_walk(tv, ["Brightness/Gamma=-3"])
        self.assertEqual(self.find(items, "Gamma").v, -3)
        self.run_walk(tv, ["Brightness/Gamma=3"])
        self.assertEqual(self.find(items, "Gamma").v, 3)
        self.run_walk(tv, ["Brightness/Brightness=0"])
        self.assertEqual(self.find(items, "Brightness").v, 0)

    def test_close_if_open_only_backs_out_of_the_panel(self):
        tv = FakeTV(picture_menu())
        panel = sonyui.Panel(tv, log=lambda *a: None, settle=0)
        panel.close_if_open()
        self.assertEqual(getattr(tv, "backs", 0), 1)
        tv.render = lambda: tv.__class__.render(tv).replace(sonyui.SETTINGS_PACKAGE, "com.netflix.ninja")
        panel.close_if_open()
        self.assertEqual(getattr(tv, "backs", 0), 1)

    def test_greyed_row_is_left_alone(self):
        items = picture_menu()
        tv = FakeTV(items)
        missing, changes, _ = self.run_walk(tv, ["Brightness/HDR tone mapping=Brightness preferred"])
        self.assertEqual((missing, changes), ([], []))
        self.assertNotIn("DPAD_RIGHT", tv.keys)

    def test_missing_row_is_reported(self):
        tv = FakeTV(picture_menu())
        missing, _, _ = self.run_walk(tv, ["Colour/Live Colour=Off"])
        self.assertEqual(missing, [("Colour", "Live Colour", None, "Off")])

    def test_dropped_keys_are_retried(self):
        items = picture_menu()
        tv = FakeTV(items)
        tv.drop_next = 1
        missing, _, _ = self.run_walk(tv, ["Basic/Picture mode=Standard"])
        self.assertEqual(missing, [])
        self.assertEqual(self.find(items, "Picture mode").value, "Standard")

    def test_read_only_walk_lists_every_row(self):
        items = picture_menu()
        tv = FakeTV(items)
        _, changes, rows = self.run_walk(tv, [], read_only=True)
        self.assertEqual(changes, [])
        self.assertIn(("Brightness", "Peak luminance"), rows)
        self.assertIn(("Motion", "Film mode"), rows)
        self.assertNotIn("DPAD_LEFT", tv.keys)
        self.assertNotIn("DPAD_RIGHT", tv.keys)

    def test_panel_must_stay_on_screen(self):
        tv = FakeTV(picture_menu())
        panel = sonyui.Panel(tv, log=lambda *a: None, settle=0)
        tv.render = lambda: tv.__class__.render(tv).replace(sonyui.SETTINGS_PACKAGE, "com.netflix.ninja")
        with self.assertRaises(SystemExit):
            panel.open("picture")


if __name__ == "__main__":
    unittest.main()
