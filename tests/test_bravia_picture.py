"""tools/bravia.py against the answers a real XR-83A80L gave, and a simulated TV for everything that writes.

The fixtures in tests/fixtures/bravia/ were captured from the TV: picture-sdr-vivid.json on the home screen (SDR,
Vivid) and picture-dv-vivid.json with a Dolby Vision title playing. What no TV reproduces on demand is tested
against FakeTv: a mode switch that changes what is adjustable, a setting the TV refuses (40004), one it accepts
but does not take, a key it rejects (401), and a record that has to survive a second run.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import base64
import contextlib
import copy
from http.client import HTTPMessage
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import threading
import time
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests" / "fixtures" / "bravia"
HOST = "192.168.1.50"
PSK = "0000"

_spec = importlib.util.spec_from_file_location("bravia", ROOT / "tools" / "bravia.py")
bravia = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bravia)


def capture(name):
    """The list of settings exactly as the TV answered getPictureQualitySettings."""
    with open(FIXTURES / name, encoding="utf-8") as f:
        return json.load(f)["result"][0]


def with_values(entries, **changes):
    """A copy of a capture with some fields changed: target=dict of fields."""
    entries = copy.deepcopy(entries)
    for entry in entries:
        entry.update(changes.get(entry["target"], {}))
    return entries


class FakeTv:
    """A BRAVIA that answers the JSON-RPC calls bravia.py makes, with the shapes the real one used.

    Each picture mode keeps its own values and its own idea of what is adjustable, as on the TV, so a switch
    changes what the next read shows. Writes need the pre-shared key or the paired cookie, as on the TV.
    """

    def __init__(self, fixture, power="off", modes=None, available=None, psk=PSK, cookie=None):
        entries = capture(fixture)
        self.shape = copy.deepcopy(entries)
        self.mode = next(e["currentValue"] for e in entries if e["target"] == "pictureMode")
        names = [c["value"] for e in entries if e["target"] == "pictureMode" for c in e["candidate"]]
        base = {e["target"]: e["currentValue"] for e in entries if e["target"] != "pictureMode"}
        base_available = {e["target"]: e["isAvailable"] for e in entries}
        self.values = {name: dict(base, **(modes or {}).get(name, {})) for name in names}
        self.available = {name: dict(base_available, **(available or {}).get(name, {})) for name in names}
        self.power = power
        self.psk = psk
        self.cookie = cookie
        self.writes = []
        self.requests = []
        self.refuse = {}      # target -> error code; the value is not taken
        self.ignore = set()   # accepted without an error, but the value does not change
        self.read_error = None

    def entries(self):
        out = []
        for entry in copy.deepcopy(self.shape):
            target = entry["target"]
            entry["currentValue"] = self.mode if target == "pictureMode" else self.values[self.mode][target]
            entry["isAvailable"] = self.available[self.mode][target]
            out.append(entry)
        return out

    def http(self, url, body, headers):
        self.requests.append((url, body, dict(headers)))
        service = url.rsplit("/", 1)[-1]
        method, params = body["method"], body["params"]

        def answer(result=None, error=None, status=200):
            payload = {"id": body["id"]}
            payload.update({"error": error} if error else {"result": result})
            return status, HTTPMessage(), json.dumps(payload).encode()

        authorised = ((self.psk and headers.get("X-Auth-PSK") == self.psk)
                      or (self.cookie and headers.get("Cookie") == f"auth={self.cookie}"))
        if method.startswith("set") and not authorised:
            return answer(error=[401, "Unauthorized"], status=401)
        if (service, method) == ("video", "getPictureQualitySettings"):
            if self.read_error:
                return answer(error=list(self.read_error))
            return answer([self.entries()])
        if (service, method) == ("video", "setPictureQualitySettings"):
            for item in params[0]["settings"]:
                target, value = item["target"], item["value"]
                self.writes.append((target, value))
                if target in self.refuse:
                    return answer(error=[self.refuse[target], "Partial failure"])
                if target in self.ignore:
                    continue
                if target == "pictureMode":
                    self.mode = value
                else:
                    self.values[self.mode][target] = value
            return answer([])
        if (service, method) == ("system", "getPowerSavingMode"):
            return answer([{"mode": self.power}])
        if (service, method) == ("system", "setPowerSavingMode"):
            self.writes.append(("powerSaving", params[0]["mode"]))
            self.power = params[0]["mode"]
            return answer([])
        return answer(error=[12, "No Such Method"])


class ToolCase(unittest.TestCase):
    """A temporary HOME for the record and the pairing file, TV_HOST and a pre-shared key."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.home = Path(tmp.name)
        patcher = mock.patch.dict(os.environ, {"HOME": str(self.home), "USERPROFILE": str(self.home),
                                               "TV_HOST": HOST, "BRAVIA_PSK": PSK})
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_tool(self, tv, *argv, read_pin=None):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                code = bravia.main(list(argv), http=tv.http if tv else None, read_pin=read_pin,
                                   sleep=lambda seconds: None)
            except SystemExit as e:
                if isinstance(e.code, str):
                    err.write(e.code)
                code = 1 if isinstance(e.code, str) else (e.code or 0)
        return code, out.getvalue(), err.getvalue()

    def record_file(self):
        return self.home / ".cleanyourtv" / f"bravia-picture-{HOST}.json"

    def record(self):
        path = self.record_file()
        return json.loads(path.read_text()) if path.exists() else None


class SignalAndPlanTest(unittest.TestCase):
    def test_family_of_both_captures(self):
        self.assertEqual(bravia.signal_family(capture("picture-sdr-vivid.json")), "sdr-hdr")
        self.assertEqual(bravia.signal_family(capture("picture-dv-vivid.json")), "dv")
        self.assertIsNone(bravia.signal_family([e for e in capture("picture-sdr-vivid.json")
                                                if e["target"] != "pictureMode"]))

    def test_calibrated_entry_does_not_hide_dolby_vision(self):
        # Netflix in Dolby Vision with Netflix calibrated mode on: the list is dvDark, dvBright, dvVivid, calibrated.
        entries = capture("picture-dv-netflix-calibrated.json")
        self.assertEqual(bravia.signal_family(entries), "dv")

    def test_tuned_mode_per_family(self):
        sdr = bravia.settings_of(capture("picture-sdr-vivid.json"))
        dv = bravia.settings_of(capture("picture-dv-vivid.json"))
        self.assertEqual(bravia.tuned_mode("sdr-hdr", sdr["pictureMode"]["candidate"]), "custom")
        self.assertEqual(bravia.tuned_mode("dv", dv["pictureMode"]["candidate"]), "dvBright")

    def test_no_custom_mode_refuses_with_the_candidates(self):
        entries = capture("picture-sdr-vivid.json")
        mode = next(e for e in entries if e["target"] == "pictureMode")
        mode["candidate"] = [c for c in mode["candidate"] if c["value"] != "custom"]
        with self.assertRaises(bravia.PlanError) as caught:
            bravia.tuned_mode(bravia.signal_family(entries), mode["candidate"])
        self.assertIn("vivid, standard, cinema, game, graphics, photo", str(caught.exception))
        self.assertIn("nothing was changed", str(caught.exception))

    def test_plan_for_sdr_tunes_custom(self):
        # What the TV would answer once Custom is active: same targets, same availability as the capture.
        after_switch = with_values(capture("picture-sdr-vivid.json"), pictureMode={"currentValue": "custom"})
        steps = {s.target: s for s in bravia.plan(after_switch, "custom", {})}
        self.assertEqual([s.target for s in bravia.plan(after_switch, "custom", {})],
                         [target for target, _ in bravia.PROFILE])
        self.assertEqual((steps["lightSensor"].action, steps["lightSensor"].value), ("set", "off"))
        self.assertEqual(steps["autoPictureMode"].action, "skip")
        self.assertEqual((steps["brightness"].action, steps["brightness"].value), ("already", "50"))
        self.assertEqual((steps["contrast"].action, steps["contrast"].value), ("set", "90"))
        self.assertEqual(steps["xtendedDynamicRange"].action, "already")
        self.assertEqual(steps["hdrMode"].action, "already")
        self.assertEqual(steps["colorSpace"].action, "already")

    def test_plan_for_dv_skips_the_light_sensor_only_when_unavailable(self):
        dv = with_values(capture("picture-dv-vivid.json"), pictureMode={"currentValue": "dvBright"})
        steps = {s.target: s for s in bravia.plan(dv, "dvBright", {})}
        self.assertEqual(steps["lightSensor"].action, "skip")
        self.assertIn("not adjustable in dvBright", steps["lightSensor"].reason)
        self.assertEqual(steps["contrast"].action, "set")
        self.assertEqual(steps["hdrMode"].action, "skip")

        adjustable = with_values(dv, lightSensor={"isAvailable": True})
        steps = {s.target: s for s in bravia.plan(adjustable, "dvBright", {})}
        self.assertEqual((steps["lightSensor"].action, steps["lightSensor"].value), ("set", "off"))

    def test_overrides_replace_and_extend_the_profile(self):
        sdr = with_values(capture("picture-sdr-vivid.json"), pictureMode={"currentValue": "custom"},
                          brightness={"currentValue": "30"})
        steps = bravia.plan(sdr, "custom", {"contrast": "85", "colorTemperature": "neutral",
                                            "pictureMode": "custom"})
        by_target = {s.target: s for s in steps}
        self.assertEqual(by_target["contrast"].value, "85")
        self.assertTrue(by_target["contrast"].explicit)
        self.assertEqual(steps[-1].target, "colorTemperature", "an added target comes after the profile")
        self.assertEqual(by_target["colorTemperature"].action, "set")
        self.assertNotIn("pictureMode", by_target, "the mode is the switch made before the plan")
        self.assertEqual(by_target["brightness"].value, "50", "brightness goes to the slider's maximum")

    def test_validate_takes_only_what_the_tv_offers(self):
        sdr = capture("picture-sdr-vivid.json")
        self.assertEqual(bravia.validate(sdr, "brightness", "50"), "50")
        self.assertEqual(bravia.validate(sdr, "brightness", "max"), "50")
        self.assertEqual(bravia.validate(sdr, "brightness", "min"), "0")
        self.assertEqual(bravia.validate(sdr, "hue", "-50"), "-50")
        self.assertEqual(bravia.validate(sdr, "colorTemperature", "Neutral"), "neutral")
        for target, value, says in (("brightness", "51", "0..50"), ("brightness", "-1", "0..50"),
                                    ("hue", "-51", "-50..50"), ("contrast", "90.5", "whole number"),
                                    ("colorTemperature", "sepia", "cool, neutral, warm, expert1, expert2"),
                                    ("xtendedDynamicRange", "max", "high, medium, low, off"),
                                    ("gamma", "0", "not a picture setting")):
            with self.subTest(target=target, value=value):
                with self.assertRaises(bravia.PlanError) as caught:
                    bravia.validate(sdr, target, value)
                self.assertIn(says, str(caught.exception))
        stepped = with_values(sdr, sharpness={"candidate": [{"min": 0, "max": 100, "step": 5}]})
        self.assertEqual(bravia.validate(stepped, "sharpness", "55"), "55")
        with self.assertRaises(bravia.PlanError):
            bravia.validate(stepped, "sharpness", "52")


class RecordTest(ToolCase):
    def test_first_value_recorded_wins(self):
        record = bravia.merge_record(None, "sdr-hdr", {
            "taken": "t1", "host": HOST, "powerSaving": "low", "originalMode": "vivid"})
        record = bravia.merge_record(record, "sdr-hdr", {
            "taken": "t1", "mode": "custom", "values": {"brightness": "40", "contrast": "80", "pictureMode": "x"}})
        # A second run sees the tuned TV: none of it may replace what was there before.
        again = bravia.merge_record(record, "sdr-hdr", {
            "taken": "t2", "powerSaving": "off", "originalMode": "custom", "mode": "custom",
            "values": {"brightness": "50", "contrast": "90", "sharpness": "50"}})
        self.assertEqual(again["powerSaving"], "low")
        self.assertEqual(again["created"], "t1")
        entry = again["labels"]["sdr-hdr"]
        self.assertEqual(entry["originalMode"], "vivid")
        self.assertEqual(entry["modes"]["custom"], {"brightness": "40", "contrast": "80", "sharpness": "50"})
        self.assertEqual(record["labels"]["sdr-hdr"]["modes"]["custom"]["brightness"], "40",
                         "merge_record returns a copy")
        other = bravia.merge_record(again, "dv", {"taken": "t3", "originalMode": "dvVivid"})
        self.assertEqual(sorted(other["labels"]), ["dv", "sdr-hdr"])

    def test_record_is_written_atomically(self):
        path = self.record_file()
        bravia.write_record(path, {"version": 1, "labels": {"a": {}}})
        self.assertEqual(json.loads(path.read_text())["labels"], {"a": {}})
        self.assertEqual(list(path.parent.glob("*.tmp")), [], "nothing left next to it")
        # A crash between writing and moving it into place leaves the previous record whole.
        with mock.patch.object(bravia.os, "replace", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                bravia.write_record(path, {"version": 1, "labels": {"b": {}}})
        self.assertEqual(json.loads(path.read_text())["labels"], {"a": {}})

    def test_damaged_record_is_left_alone(self):
        tv = FakeTv("picture-sdr-vivid.json")
        path = self.record_file()
        path.parent.mkdir(parents=True)
        path.write_text("{not json")
        code, _, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 1)
        self.assertIn("not a usable picture record", err)
        self.assertEqual(path.read_text(), "{not json")
        self.assertEqual(tv.writes, [])

    def test_restore_order(self):
        record = {"version": 1, "powerSaving": "low", "labels": {"sdr-hdr": {
            "originalMode": "vivid",
            "modes": {"custom": {"autoPictureMode": "auto", "brightness": "40", "lightSensor": "on"}}}}}
        self.assertEqual(bravia.restore_plan(record, "sdr-hdr"), [
            ("pictureMode", "custom"),
            ("brightness", "40"),
            ("lightSensor", "on"),
            # Content-based switching comes back last, so it cannot move the TV off the mode mid-restore.
            ("autoPictureMode", "auto"),
            ("pictureMode", "vivid"),
            ("powerSaving", "low"),
        ])
        with self.assertRaises(bravia.PlanError) as caught:
            bravia.restore_plan(record, "dv")
        self.assertIn("recorded: sdr-hdr", str(caught.exception))


class SetCookieTest(unittest.TestCase):
    def test_max_age(self):
        headers = HTTPMessage()
        headers["Set-Cookie"] = "auth=d5f1c2; Path=/sony/; Max-Age=1209600; Expires=Wed, 05 Nov 2026 10:00:00 GMT"
        self.assertEqual(bravia.parse_set_cookie(headers, now=1000), ("d5f1c2", 1000 + 1209600),
                         "Max-Age wins over Expires")

    def test_expires_in_netscape_form(self):
        value, expires = bravia.parse_set_cookie(["auth=abc; Path=/sony/; Expires=Wed, 05-Nov-2026 10:00:00 GMT"])
        self.assertEqual(value, "abc")
        self.assertEqual(time.strftime("%Y-%m-%d %H:%M", time.gmtime(expires)), "2026-11-05 10:00")

    def test_other_cookies_and_no_cookie(self):
        self.assertEqual(bravia.parse_set_cookie([("Content-Type", "x"), ("Set-Cookie", "session=1"),
                                                  ("Set-Cookie", "auth=zz")]), ("zz", None))
        self.assertIsNone(bravia.parse_set_cookie(HTTPMessage()))
        self.assertIsNone(bravia.parse_set_cookie({"Set-Cookie": "auth=; Max-Age=0"}), "an empty auth clears it")


class ApplyTest(ToolCase):
    def sdr_tv(self, **kw):
        # Custom has values of its own, and the light sensor is adjustable there but not in Vivid: the plan has to
        # come from a read made after the switch.
        return FakeTv("picture-sdr-vivid.json", power=kw.pop("power", "low"),
                      modes={"custom": {"lightSensor": "on", "brightness": "40", "contrast": "80",
                                        "autoPictureMode": "auto", "colorTemperature": "expert1"}},
                      available={"vivid": {"lightSensor": False}, "custom": {"lightSensor": True,
                                                                            "autoPictureMode": True}}, **kw)

    def test_apply_end_to_end(self):
        tv = self.sdr_tv()
        code, out, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 0, err + out)
        self.assertEqual(tv.writes, [
            ("pictureMode", "custom"),
            ("lightSensor", "off"),
            ("autoPictureMode", "off"),
            ("brightness", "50"),
            ("contrast", "90"),
            ("powerSaving", "off"),
        ])
        self.assertEqual(tv.mode, "custom")
        self.assertEqual(tv.values["vivid"]["contrast"], "100", "the mode left behind is not touched")
        record = self.record()
        self.assertEqual(record["powerSaving"], "low")
        entry = record["labels"]["sdr-hdr"]
        self.assertEqual(entry["originalMode"], "vivid")
        # Custom's own values from before, read after the switch, not Vivid's.
        self.assertEqual(entry["modes"]["custom"]["brightness"], "40")
        self.assertEqual(entry["modes"]["custom"]["contrast"], "80")
        self.assertEqual(entry["modes"]["custom"]["lightSensor"], "on")
        self.assertNotIn("pictureMode", entry["modes"]["custom"])
        self.assertIn("contrast", out)
        # Vivid as it was, Custom's own value after the switch, Custom now.
        self.assertRegex(out, r"vivid before\s+custom before\s+custom after")
        self.assertRegex(out, r"contrast\s+100\s+80\s+90\s+set")
        self.assertRegex(out, r"powerSaving\s+low\s+off\s+set")

    def test_second_apply_keeps_the_first_record(self):
        tv = self.sdr_tv()
        self.assertEqual(self.run_tool(tv, "picture", "apply")[0], 0)
        first = self.record()
        tv.values["custom"]["contrast"] = "70"
        code, _, err = self.run_tool(tv, "picture", "apply", "--set", "colorTemperature=neutral")
        self.assertEqual(code, 0, err)
        record = self.record()
        self.assertEqual(record["labels"]["sdr-hdr"]["modes"]["custom"]["contrast"], "80")
        self.assertEqual(record["labels"]["sdr-hdr"]["originalMode"], "vivid")
        self.assertEqual(record["powerSaving"], first["powerSaving"])
        self.assertEqual(tv.values["custom"]["colorTemperature"], "neutral")

    def test_dolby_vision_tunes_dv_bright(self):
        tv = FakeTv("picture-dv-vivid.json")
        code, out, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 0, err + out)
        self.assertEqual(tv.mode, "dvBright")
        self.assertEqual(tv.writes, [("pictureMode", "dvBright"), ("contrast", "90")])
        self.assertIn("skipped: not adjustable in dvBright", out)
        entry = self.record()["labels"]["dv"]
        self.assertEqual(entry["originalMode"], "dvVivid")
        self.assertEqual(entry["modes"]["dvBright"]["lightSensor"], "on")

    def test_a_refused_setting_is_named_and_the_rest_still_applies(self):
        tv = self.sdr_tv()
        tv.refuse["contrast"] = 40004
        code, out, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 1)
        self.assertIn("40004", out)
        self.assertIn("did not take: contrast (wanted 90, the TV says 80)", err)
        self.assertEqual(tv.values["custom"]["lightSensor"], "off")
        self.assertEqual(tv.power, "off")

    def test_a_value_that_does_not_take_exits_1(self):
        tv = FakeTv("picture-sdr-vivid.json", modes={"custom": {"xtendedDynamicRange": "medium"}})
        tv.ignore.add("xtendedDynamicRange")
        code, out, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 1)
        self.assertIn("xtendedDynamicRange (wanted high, the TV says medium)", err)
        self.assertRegex(out, r"xtendedDynamicRange\s+high\s+medium\s+medium\s+DID NOT TAKE")

    def test_without_authorisation_nothing_is_written(self):
        del os.environ["BRAVIA_PSK"]
        tv = self.sdr_tv()
        code, _, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 1)
        self.assertIn("bravia.py pair", err)
        self.assertEqual(tv.writes, [])
        self.assertIsNone(self.record(), "nothing recorded either")

    def test_a_rejected_key_stops_at_the_first_write(self):
        tv = self.sdr_tv(psk="1234")
        code, _, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 1)
        self.assertIn("401", err)
        self.assertIn("nothing was changed", err)
        self.assertEqual(tv.mode, "vivid")
        self.assertEqual(tv.writes, [])
        # Nothing changed, so this run's reading must not win over the next one's.
        self.assertIsNone(self.record())
        # The owner picks Cinema and turns power saving off by hand, then fixes the key.
        tv.mode, tv.power, tv.psk = "cinema", "off", PSK
        self.assertEqual(self.run_tool(tv, "picture", "apply")[0], 0)
        record = self.record()
        self.assertEqual(record["labels"]["sdr-hdr"]["originalMode"], "cinema")
        self.assertNotIn("powerSaving", record, "power saving was off already: nothing to put back")

    def test_a_refused_first_write_leaves_an_earlier_record_as_it_was(self):
        tv = self.sdr_tv()
        self.assertEqual(self.run_tool(tv, "picture", "apply")[0], 0)
        first = self.record_file().read_text()
        tv.psk = "1234"
        code, _, err = self.run_tool(tv, "picture", "apply", "--label", "hdr10", "--set", "contrast=85")
        self.assertEqual(code, 1)
        self.assertIn("as it was before this run", err)
        self.assertEqual(self.record_file().read_text(), first)

    def test_a_refused_mode_switch_records_nothing(self):
        tv = self.sdr_tv()
        tv.refuse["pictureMode"] = 7
        code, _, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 1)
        self.assertIn("did not switch to custom", err)
        self.assertIn("still in vivid", err)
        self.assertIsNone(self.record())
        self.assertEqual(tv.writes, [("pictureMode", "custom")])

    def test_a_switch_that_never_lands_keeps_the_record(self):
        # Accepted but not reported: the switch may still land, and restore needs the record for that.
        tv = self.sdr_tv()
        tv.ignore.add("pictureMode")
        code, _, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 1)
        self.assertIn("picture restore --label sdr-hdr puts the mode back", err)
        self.assertEqual(self.record()["labels"]["sdr-hdr"]["originalMode"], "vivid")

    def test_power_saving_is_recorded_only_by_the_run_that_turns_it_off(self):
        dv = FakeTv("picture-dv-vivid.json", power="off")
        self.assertEqual(self.run_tool(dv, "picture", "apply")[0], 0)
        self.assertNotIn("powerSaving", self.record())
        # Power saving goes to Low later; the next run turns it off and has to record Low.
        tv = self.sdr_tv(power="low")
        self.assertEqual(self.run_tool(tv, "picture", "apply")[0], 0)
        self.assertEqual(self.record()["powerSaving"], "low")
        self.assertEqual(self.run_tool(tv, "picture", "restore")[0], 0)
        self.assertEqual(tv.power, "low")

    def test_a_label_holds_one_kind_of_content(self):
        self.assertEqual(self.run_tool(FakeTv("picture-sdr-vivid.json"), "picture", "apply", "--label", "movie")[0],
                         0)
        first = self.record_file().read_text()
        dv = FakeTv("picture-dv-vivid.json")
        for extra in ((), ("--dry-run",)):
            with self.subTest(extra=extra):
                code, _, err = self.run_tool(dv, "picture", "apply", "--label", "movie", *extra)
                self.assertEqual(code, 1)
                self.assertIn("label movie was recorded with SDR or HDR10/HLG on screen", err)
                self.assertIn("pass another --label", err)
        self.assertEqual(dv.writes, [])
        self.assertEqual(self.record_file().read_text(), first)

    def test_illegal_state_is_explained(self):
        tv = self.sdr_tv()
        tv.read_error = (7, "Illegal State")
        code, _, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 1)
        self.assertIn("standby", err)
        self.assertIsNone(self.record())

    def test_set_is_checked_before_anything_is_written(self):
        tv = self.sdr_tv()
        code, _, err = self.run_tool(tv, "picture", "apply", "--set", "brightness=60")
        self.assertEqual(code, 1)
        self.assertIn("0..50", err)
        code, _, err = self.run_tool(tv, "picture", "apply", "--set", "pictureMode=dvBright")
        self.assertEqual(code, 1)
        self.assertIn("vivid, standard, cinema", err)
        self.assertEqual(tv.writes, [])
        self.assertIsNone(self.record())

    def test_set_names_another_mode(self):
        tv = self.sdr_tv()
        code, _, err = self.run_tool(tv, "picture", "apply", "--set", "pictureMode=cinema")
        self.assertEqual(code, 0, err)
        self.assertEqual(tv.mode, "cinema")
        self.assertIn("cinema", self.record()["labels"]["sdr-hdr"]["modes"])

    def test_dry_run_sends_no_writes(self):
        tv = self.sdr_tv()
        code, out, err = self.run_tool(tv, "picture", "apply", "--dry-run")
        self.assertEqual(code, 0, err)
        self.assertIn("would switch pictureMode to custom", out)
        self.assertEqual(tv.writes, [])
        self.assertIsNone(self.record())


class RestoreTest(ToolCase):
    def test_restore_puts_everything_back_in_order(self):
        tv = ApplyTest.sdr_tv(self)
        self.assertEqual(self.run_tool(tv, "picture", "apply")[0], 0)
        tv.writes = []
        code, out, err = self.run_tool(tv, "picture", "restore")
        self.assertEqual(code, 0, err + out)
        # Custom is already active after apply, so no switch first; values in the TV's order, autoPictureMode last.
        self.assertEqual(tv.writes, [
            ("brightness", "40"),
            ("contrast", "80"),
            ("lightSensor", "on"),
            ("autoPictureMode", "auto"),
            ("pictureMode", "vivid"),
            ("powerSaving", "low"),
        ])
        self.assertEqual(tv.mode, "vivid")
        self.assertEqual(tv.values["custom"]["contrast"], "80")
        self.assertEqual(tv.power, "low")

    def test_restore_dry_run_sends_nothing(self):
        tv = ApplyTest.sdr_tv(self)
        self.assertEqual(self.run_tool(tv, "picture", "apply")[0], 0)
        tv.requests, tv.writes = [], []
        code, out, _ = self.run_tool(tv, "picture", "restore", "--label", "sdr-hdr", "--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("contrast", out)
        self.assertEqual(tv.requests, [], "a dry run with a label needs no TV at all")

    def test_restore_needs_the_recorded_mode_on_offer(self):
        dv = FakeTv("picture-dv-vivid.json")
        self.assertEqual(self.run_tool(dv, "picture", "apply")[0], 0)
        sdr = FakeTv("picture-sdr-vivid.json")
        code, _, err = self.run_tool(sdr, "picture", "restore", "--label", "dv")
        self.assertEqual(code, 1)
        self.assertIn("dvBright", err)
        self.assertEqual(sdr.writes, [])
        code, _, err = self.run_tool(sdr, "picture", "restore")
        self.assertEqual(code, 1)
        self.assertIn("nothing recorded for what is on screen (sdr-hdr)", err)

    def test_a_refused_switch_back_still_puts_power_saving_back(self):
        tv = ApplyTest.sdr_tv(self)
        self.assertEqual(self.run_tool(tv, "picture", "apply")[0], 0)
        tv.writes = []
        tv.refuse["pictureMode"] = 7
        code, _, err = self.run_tool(tv, "picture", "restore")
        self.assertEqual(code, 1)
        self.assertEqual(tv.writes[-2:], [("pictureMode", "vivid"), ("powerSaving", "low")])
        self.assertEqual(tv.power, "low")
        self.assertEqual(tv.values["custom"]["contrast"], "80", "Custom's values went back before the switch")
        self.assertIn("not put back: pictureMode (wanted vivid: ", err)
        self.assertIn("Illegal State", err)

    def test_values_wait_for_their_mode(self):
        tv = ApplyTest.sdr_tv(self)
        self.assertEqual(self.run_tool(tv, "picture", "apply")[0], 0)
        # Someone went back to Vivid by hand; now the TV refuses mode switches.
        tv.mode, tv.writes = "vivid", []
        vivid = dict(tv.values["vivid"])
        tv.refuse["pictureMode"] = 40004
        code, out, err = self.run_tool(tv, "picture", "restore")
        self.assertEqual(code, 1)
        self.assertEqual(tv.writes, [("pictureMode", "custom"), ("pictureMode", "vivid"), ("powerSaving", "low")],
                         "no value written while the TV is in the wrong mode")
        self.assertEqual(tv.values["vivid"], vivid)
        self.assertIn("pictureMode (wanted custom: ", err)
        # Every value recorded for Custom, in the TV's order, autoPictureMode last.
        self.assertRegex(err, r"left alone so none lands in another mode: autoLocalDimming, brightness, [^;]*, "
                              r"xtendedDynamicRange, autoPictureMode; pictureMode \(wanted vivid: ")

    def test_a_switch_that_never_lands_skips_its_values(self):
        tv = ApplyTest.sdr_tv(self)
        self.assertEqual(self.run_tool(tv, "picture", "apply")[0], 0)
        tv.mode, tv.writes = "vivid", []
        tv.ignore.add("pictureMode")
        code, _, err = self.run_tool(tv, "picture", "restore")
        self.assertEqual(code, 1)
        self.assertEqual([target for target, _ in tv.writes], ["pictureMode", "pictureMode", "powerSaving"])
        self.assertIn("pictureMode (wanted custom: the TV still reports another mode)", err)
        self.assertEqual(tv.power, "low")

    def test_labels_named_at_apply_time(self):
        tv = ApplyTest.sdr_tv(self)
        self.assertEqual(self.run_tool(tv, "picture", "apply", "--label", "sdr")[0], 0)
        self.assertEqual(self.run_tool(tv, "picture", "apply", "--label", "hdr10")[0], 0)
        self.assertEqual(self.run_tool(FakeTv("picture-dv-vivid.json"), "picture", "apply", "--label", "dv")[0], 0)
        tv.writes = []
        code, _, err = self.run_tool(tv, "picture", "restore")
        self.assertEqual(code, 1)
        self.assertIn("Recorded with this kind of content: sdr, hdr10; pass --label", err)
        self.assertEqual(tv.writes, [])
        # hdr10 found Custom already tuned by sdr, so it is undone first.
        code, out, _ = self.run_tool(tv, "picture", "restore", "--label", "sdr", "--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("note: hdr10, recorded after sdr, also covers custom: undo in reverse order, hdr10 first", out)
        code, out, _ = self.run_tool(tv, "picture", "restore", "--label", "hdr10", "--dry-run")
        self.assertNotIn("note:", out)

    def test_restore_without_a_record(self):
        code, _, err = self.run_tool(FakeTv("picture-sdr-vivid.json"), "picture", "restore")
        self.assertEqual(code, 1)
        self.assertIn("no picture record", err)


class PairTest(ToolCase):
    def pairing_tv(self, pin="1234", cookie="d5f1c2e3", answer_pin=200):
        seen = []

        def http(url, body, headers):
            seen.append((url, body, dict(headers)))
            if not headers.get("Authorization"):
                return 401, HTTPMessage(), json.dumps({"error": [401, "Unauthorized"], "id": body["id"]}).encode()
            if headers["Authorization"] != "Basic " + base64.b64encode(f":{pin}".encode()).decode():
                return 401, HTTPMessage(), b""
            reply = HTTPMessage()
            reply["Set-Cookie"] = f"auth={cookie}; Path=/sony/; Max-Age=1209600"
            return answer_pin, reply, json.dumps({"result": [], "id": body["id"]}).encode()
        return http, seen

    def pair(self, http, pin="1234"):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                return bravia.main(["pair"], http=http, read_pin=lambda: pin), err.getvalue()
            except SystemExit as e:
                return e.code, err.getvalue()

    def auth_file(self):
        return self.home / ".cleanyourtv" / f"bravia-{HOST}.json"

    def test_pair_keeps_the_cookie_private_and_writes_use_it(self):
        http, seen = self.pairing_tv()
        # A .tmp left by an earlier crash, readable by everyone: the result must still be 0600.
        self.auth_file().parent.mkdir(parents=True)
        tmp = self.auth_file().with_name(self.auth_file().name + ".tmp")
        tmp.write_text("{}")
        tmp.chmod(0o644)
        code, err = self.pair(http)
        self.assertEqual(code, 0, err)
        self.assertEqual(stat.S_IMODE(os.stat(self.auth_file()).st_mode), 0o600)
        saved = json.loads(self.auth_file().read_text())
        self.assertEqual(saved["cookie"], "d5f1c2e3")
        self.assertAlmostEqual(saved["expires"], time.time() + 1209600, delta=60)
        self.assertRegex(saved["clientid"], r"^cleanyourtv:[0-9a-f-]{36}$")

        self.assertEqual(len(seen), 2)
        params = seen[0][1]["params"]
        self.assertEqual(seen[0][0], f"http://{HOST}/sony/accessControl")
        self.assertEqual(seen[0][1]["method"], "actRegister")
        self.assertEqual(params[0]["clientid"], saved["clientid"])
        self.assertEqual(params[0]["level"], "private")
        self.assertTrue(params[0]["nickname"].startswith("CleanYourTV ("))
        self.assertEqual(params[1], [{"function": "WOL", "value": "yes"}])
        self.assertEqual(seen[1][1]["params"], params, "the PIN goes back with the same registration")

        # The cookie authorises writes once there is no pre-shared key.
        del os.environ["BRAVIA_PSK"]
        tv = FakeTv("picture-sdr-vivid.json", psk=None, cookie="d5f1c2e3")
        code, _, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 0, err)
        self.assertIn(("pictureMode", "custom"), tv.writes)

    def test_pairing_again_keeps_the_client_id(self):
        http, _ = self.pairing_tv()
        self.assertEqual(self.pair(http)[0], 0)
        first = json.loads(self.auth_file().read_text())["clientid"]
        self.assertEqual(self.pair(http)[0], 0)
        self.assertEqual(json.loads(self.auth_file().read_text())["clientid"], first)

    def test_wrong_pin_pairs_nothing(self):
        http, _ = self.pairing_tv(pin="1234")
        code, err = self.pair(http, pin="9999")
        self.assertNotEqual(code, 0)
        self.assertNotIn("cookie", json.loads(self.auth_file().read_text()))

    def test_the_pin_is_never_an_argument(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as caught:
                bravia.main(["pair", "1234"], http=self.pairing_tv()[0])
        self.assertEqual(caught.exception.code, 2)

    def test_the_pin_prompt_needs_a_terminal(self):
        with mock.patch.object(bravia.sys, "stdin", io.StringIO("1234\n")):
            with self.assertRaises(SystemExit) as caught:
                bravia.prompt_pin()
        self.assertIn("terminal", str(caught.exception.code))

    def test_the_pin_is_ascii_digits(self):
        terminal = mock.Mock()
        terminal.isatty.return_value = True
        # Devanagari and full-width digits, as an Indian or Japanese keyboard types them.
        for typed in ("१२३४", "１２３４", " 1234 "):
            with self.subTest(typed=typed):
                with mock.patch.object(bravia.sys, "stdin", terminal), \
                        mock.patch("builtins.input", return_value=typed):
                    self.assertEqual(bravia.prompt_pin(), "1234")
        self.assertIsNone(bravia.PIN_RE.fullmatch("१२३४"))
        # A PIN that bypasses the prompt is checked again, not sent as a header it cannot be.
        http, seen = self.pairing_tv()
        code, _ = self.pair(http, pin="１２３４")
        self.assertIn("4 digits", str(code))
        self.assertEqual(len(seen), 1)

    def test_an_expired_cookie_is_not_used(self):
        del os.environ["BRAVIA_PSK"]
        bravia.write_json(self.auth_file(), {"clientid": "cleanyourtv:x", "cookie": "old",
                                             "expires": time.time() - 60}, private=True)
        auth = bravia.auth_state(HOST)
        self.assertFalse(auth.ok)
        self.assertIn("ran out", auth.note)
        tv = FakeTv("picture-sdr-vivid.json", psk=None, cookie="old")
        code, _, err = self.run_tool(tv, "picture", "apply")
        self.assertEqual(code, 1)
        self.assertEqual(tv.writes, [])


class HttpTransportTest(ToolCase):
    """The real urllib path, against a stand-in TV on 127.0.0.1: the 401 that carries the PIN prompt, the
    Set-Cookie header, and the cookie going back on a write."""

    def setUp(self):
        super().setUp()
        seen = self.seen = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                seen.append((self.path, body["method"], dict(self.headers)))
                status, extra, payload = 200, {}, {"result": [], "id": body["id"]}
                if body["method"] == "actRegister" and not self.headers.get("Authorization"):
                    status, payload = 401, {"error": [401, "Unauthorized"], "id": body["id"]}
                elif body["method"] == "actRegister":
                    if self.headers["Authorization"] != "Basic " + base64.b64encode(b":4321").decode():
                        status, payload = 401, {"error": [401, "Unauthorized"], "id": body["id"]}
                    else:
                        extra = {"Set-Cookie": "auth=c0ffee; Path=/sony/; Max-Age=1209600"}
                elif body["method"] == "getPowerSavingMode":
                    payload["result"] = [{"mode": "off"}]
                elif body["method"].startswith("set") and self.headers.get("Cookie") != "auth=c0ffee":
                    status, payload = 403, {"error": [403, "Forbidden"], "id": body["id"]}
                data = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                for key, value in extra.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(data)

        try:
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        except OSError as e:
            self.skipTest(f"cannot listen on 127.0.0.1 here ({e})")
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.host = f"127.0.0.1:{server.server_address[1]}"
        # A proxy in the environment must not be used for the TV.
        os.environ.update(TV_HOST=self.host, http_proxy="http://127.0.0.1:9", HTTP_PROXY="http://127.0.0.1:9")
        del os.environ["BRAVIA_PSK"]

    def test_pair_then_write_over_http(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(bravia.pair(self.host, read_pin=lambda: "4321"), 0)
        self.assertEqual([method for _, method, _ in self.seen], ["actRegister", "actRegister"])
        self.assertEqual(self.seen[0][0], "/sony/accessControl")
        auth = bravia.auth_state(self.host)
        self.assertTrue(auth.ok)
        self.assertEqual(auth.headers, {"Cookie": "auth=c0ffee"})
        post = bravia.make_post(self.host, auth.headers)
        self.assertEqual(bravia.read_power_saving(post), "off")
        bravia.set_power_saving(post, "off")
        with self.assertRaises(bravia.AuthError):
            bravia.set_power_saving(bravia.make_post(self.host, {}), "off")

    def test_wrong_pin_over_http(self):
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit) as caught:
                bravia.pair(self.host, read_pin=lambda: "1111")
        self.assertIn("did not accept that PIN", str(caught.exception.code))


class CommandsTest(ToolCase):
    def test_tv_host_is_required(self):
        del os.environ["TV_HOST"]
        code, _, err = self.run_tool(None, "status")
        self.assertEqual(code, 1)
        self.assertIn("Set TV_HOST", err)

    def test_status_and_get(self):
        tv = FakeTv("picture-dv-vivid.json")
        code, out, _ = self.run_tool(tv, "status")
        self.assertEqual(code, 0)
        self.assertIn("Dolby Vision", out)
        self.assertRegex(out, r"lightSensor\s+on\s+no\s+on, off")
        self.assertIn("pre-shared key", out)
        code, out, _ = self.run_tool(tv, "status", "--json")
        self.assertEqual(json.loads(out)["family"], "dv")
        code, out, _ = self.run_tool(tv, "get", "brightness")
        self.assertIn("brightness = 50 (adjustable in dvVivid; candidates: 0..50)", out)
        self.assertEqual(tv.writes, [])

    def test_call(self):
        tv = FakeTv("picture-sdr-vivid.json", power="low")
        code, out, _ = self.run_tool(tv, "call", "system", "getPowerSavingMode", "[]")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out), [{"mode": "low"}])
        code, _, err = self.run_tool(tv, "call", "../system", "getPowerSavingMode", "[]")
        self.assertEqual(code, 1)
        self.assertIn("not a service name", err)


if __name__ == "__main__":
    unittest.main()
