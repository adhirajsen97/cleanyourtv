"""Run tools/screensaver.sh against a simulated TV, without a device.

The script changes settings that tools/recovery.py does not record, so the parts worth testing are the
ones that decide what reaches the TV and whether it can be undone: the component check, the order of
the writes, the undo block written before them, the read-back that catches a value that did not take,
and --test putting the idle time back whatever happens.

tools/tv.py is replaced by a fake that runs each command through a real `sh` whose PATH holds only
fake `settings`, `pm`, `cmd`, `dumpsys` and friends, so the script's own shell syntax (loops,
command substitution, chained commands) is what gets exercised, and nothing else can run.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
GOOGLE = "com.google.android.apps.tv.dreamx/com.google.android.apps.tv.dreamx.service.Backdrop"
GOOGLE_PKG = "com.google.android.apps.tv.dreamx"
CLOCK = "com.gathin.tvhome/.ClockDream"
CLOCK_FULL = "com.gathin.tvhome/com.gathin.tvhome.ClockDream"
# A documentation address (TEST-NET-1): nothing here may ever look like a TV on the LAN.
HOST = "192.0.2.10"

# Stands in for tools/tv.py. Like the real one it exits 0 whatever the TV's shell says.
FAKE_TV = r'''
import json
import os
import subprocess
import sys

STATE_PATH = os.environ["FAKE_TV_STATE"]
LOG_PATH = os.environ["FAKE_TV_UNDO_LOG"]


def load():
    with open(STATE_PATH) as f:
        return json.load(f)


def save(state):
    with open(STATE_PATH, "w") as f:
        json.dump(state, f)


def main():
    args = [a for a in sys.argv[1:] if a != "-I"]
    if args and args[0].endswith("tv.py"):
        args = args[1:]
    state = load()
    if state["offline"]:
        sys.stderr.write("OSError: [Errno 61] Connection refused\n")
        return 1
    if args[:1] == ["--pull"]:
        remote, local = args[1], args[2]
        state["pulls"].append([remote, local])
        save(state)
        if remote not in state["files"]:
            sys.stderr.write("adb_shell.exceptions.AdbCommandFailureException: no such file\n")
            return 1
        with open(local, "wb") as f:
            f.write(b"\x89PNG\r\n\x1a\nfake screenshot")
        print(f"pulled {remote} -> {local}")
        return 0
    command = " ".join(args)
    state["commands"].append(command)
    # How much undo was on disk when this command reached the TV.
    state["log_size"].append(os.path.getsize(LOG_PATH) if os.path.exists(LOG_PATH) else 0)
    save(state)
    result = subprocess.run(["/bin/sh", "-c", command], capture_output=True, text=True,
                            env={"PATH": os.environ["FAKE_TV_BIN"], "FAKE_TV_STATE": STATE_PATH})
    # adb shells answer with CRLF on some builds; the script has to cope.
    sys.stdout.write((result.stdout + result.stderr).replace("\n", "\r\n"))
    return 0


sys.exit(main())
'''

# The TV's command-line tools, one script behind every name in the fake PATH.
FAKE_ANDROID = r'''
import json
import os
import sys

STATE_PATH = os.environ["FAKE_TV_STATE"]


def full(component):
    pkg, cls = component.split("/", 1)
    return f"{pkg}/{pkg}{cls}" if cls.startswith(".") else component


def short(component):
    pkg, cls = full(component).split("/", 1)
    return f"{pkg}/{cls[len(pkg):]}" if cls.startswith(pkg + ".") else component


def pop_user(args, default):
    """--user N anywhere in the arguments, as Android 12's settings and pm parse it."""
    if "--user" in args:
        i = args.index("--user")
        return int(args[i + 1]), args[:i] + args[i + 2:]
    return default, args


# User 0 is the main profile; any other user is the second Google TV profile, with its own copies.
def settings_of(state, user):
    return state["settings"] if user == 0 else state["profile_settings"]


def disabled_of(state, user):
    return state["disabled"] if user == 0 else state["profile_disabled"]


def current_dream(state):
    settings = settings_of(state, state["current_user"])
    timeout = int(settings["system"].get("screen_off_timeout", "600000"))
    if state["dream_starts"] and state["idle_s"] * 1000 >= timeout:
        return full(settings["secure"]["screensaver_components"])
    return None


def run(state, tool, args):
    if tool == "settings":
        # Without --user, settings acts on the profile in front, like Android 12's SettingsService.
        user, args = pop_user(args, state["current_user"])
        settings = settings_of(state, user)
        op, namespace = args[0], args[1]
        if op == "list":
            return "".join(f"{k}={v}\n" for k, v in sorted(settings[namespace].items()))
        key = args[2]
        if op == "get":
            return settings[namespace].get(key, "null") + "\n"
        if op == "put":
            if key not in state["stuck"] and [key, args[3]] not in state["ignored_puts"]:
                settings[namespace][key] = args[3]
            return ""
        if op == "delete":
            settings[namespace].pop(key, None)
            return "Deleted 1 rows\n"
    if tool == "pm":
        # pm defaults to user 0 (USER_SYSTEM).
        if args[:2] == ["list", "packages"]:
            user, rest = pop_user(args[2:], 0)
            if rest == ["-d"]:
                return "".join(f"package:{p}\n" for p in disabled_of(state, user))
        if args[:1] == ["enable"]:
            user, rest = pop_user(args[1:], 0)
            disabled = disabled_of(state, user)
            if rest[0] in disabled:
                disabled.remove(rest[0])
            return f"Package {rest[0]} new state: enabled\n"
        return ""
    if tool == "cmd" and args[:2] == ["package", "query-services"]:
        # query-services defaults to the profile in front (USER_CURRENT).
        user, args = pop_user(args, state["current_user"])
        disabled = disabled_of(state, user)
        found = [d for d in state["dreams"] if d.split("/")[0] not in disabled]
        if not found:
            return "No services found\n"
        lines = [f"{len(found)} services found:"]
        for i, dream in enumerate(found):
            lines += [f"  Service #{i}:",
                      "    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false",
                      f"    {short(dream)}"]
        return "\n".join(lines) + "\n"
    if tool == "dumpsys" and args == ["dreams"]:
        dream = current_dream(state)
        name = f"ComponentInfo{{{dream}}}" if dream else "null"
        if state["dream_format"] == "record":
            current = (f"DreamRecord{{token=android.os.Binder@3c1, name={name}, userId=0}}"
                       if dream else "null")
            return ("DREAM MANAGER (dumpsys dreams)\n\nmCurrentDream=" + current + "\n"
                    "mForceAmbientDisplayEnabled=false\n")
        return ("DREAM MANAGER (dumpsys dreams)\n\nmCurrentDreamToken=android.os.Binder@3c1\n"
                f"mCurrentDreamName={name}\nmCurrentDreamUserId=0\nmCurrentDreamIsTest=false\n"
                "getDozeComponent()=null\n\nDreamland:\n  mCurrentDream: null\n")
    if tool == "sleep":
        state["idle_s"] += int(args[0])
        return ""
    if tool == "input":
        if args == ["keyevent", "KEYCODE_WAKEUP"]:
            state["idle_s"] = 0
        return ""
    if tool == "screencap":
        state["files"].append(args[-1])
        return ""
    if tool == "rm":
        state["files"] = [f for f in state["files"] if f not in args]
        return ""
    return ""


def main():
    tool, args = sys.argv[1], sys.argv[2:]
    with open(STATE_PATH) as f:
        state = json.load(f)
    state["calls"].append([tool] + args)
    out = run(state, tool, args)
    with open(STATE_PATH, "w") as f:
        json.dump(state, f)
    sys.stdout.write(out)


main()
'''

TOOLS_ON_TV = ("settings", "pm", "cmd", "dumpsys", "sleep", "input", "screencap", "rm")


def profile_settings():
    """User 10, the TV's second Google TV profile: the debloat lists never touched it."""
    return {
        "secure": {
            "screensaver_components": "com.google.android.apps.tv.dreamx/.service.Backdrop",
            "screensaver_enabled": "1",
            "screensaver_activate_on_sleep": "1",
        },
        "system": {"screen_off_timeout": "900000"},
    }


def today():
    """The XR-83A80L as read on 2026-10-20, after debloat batch 6 disabled Ambient mode."""
    return {
        "settings": {
            "secure": {
                "screensaver_components": CLOCK,
                "screensaver_enabled": "1",
                "screensaver_default_component": "com.google.android.apps.tv.dreamx/.service.Backdrop",
                "screensaver_activate_on_sleep": "1",
                "screensaver_activate_on_dock": "1",
                "sleep_timeout": "7200000",
                "attentive_timeout": "7200000",
                "default_input_method": "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
                "screensaver_components_backup": "com.example/.NotThisOne",
            },
            "system": {"screen_off_timeout": "600000", "screen_brightness": "102"},
        },
        "disabled": [GOOGLE_PKG, "com.google.android.apps.tv.launcherx"],
        "current_user": 0,
        "profile_settings": profile_settings(),
        "profile_disabled": [],
        "dreams": [GOOGLE, CLOCK_FULL],
        "stuck": [],
        # [key, value] writes the TV ignores, while other values for the same key take.
        "ignored_puts": [],
        "dream_starts": True,
        "dream_format": "android12",
        "offline": False,
        "idle_s": 0,
        "files": [],
        "commands": [],
        "log_size": [],
        "calls": [],
        "pulls": [],
    }


class ScreensaverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # The fakes are shared by every test: macOS checks each new executable the first time it
        # runs, and doing that once instead of per test saves most of this file's run time.
        tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(tmp.cleanup)
        shared = Path(tmp.name)
        cls.script = shared / "repo" / "tools" / "screensaver.sh"
        (cls.script.parent / "venv" / "bin").mkdir(parents=True)
        shutil.copy2(ROOT / "tools" / "screensaver.sh", cls.script)
        fake_tv = shared / "fake_tv.py"
        fake_tv.write_text(FAKE_TV)
        client = cls.script.parent / "venv" / "bin" / "python"
        client.write_text(f'#!/bin/sh\nexec "{sys.executable}" -S "{fake_tv}" "$@"\n')
        client.chmod(0o755)
        fake_android = shared / "fake_android.py"
        fake_android.write_text(FAKE_ANDROID)
        cls.bin = shared / "tv-bin"
        cls.bin.mkdir()
        for tool in TOOLS_ON_TV:
            wrapper = cls.bin / tool
            wrapper.write_text(f'#!/bin/sh\nexec "{sys.executable}" -S "{fake_android}" {tool} "$@"\n')
            wrapper.chmod(0o755)

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.home = self.base / "home"
        self.home.mkdir()
        # Run from somewhere else: the script has to find the repo by itself.
        self.cwd = self.base / "elsewhere"
        self.cwd.mkdir()
        self.state_path = self.base / "tv-state.json"
        self.log = self.home / ".cleanyourtv" / f"screensaver-{HOST}.log"

    def run_script(self, *args, state=None, env=None):
        self.state_path.write_text(json.dumps(state or today()))
        full_env = {k: v for k, v in os.environ.items() if k not in ("SCREENSAVER_SHOT", "TV_HOST")}
        full_env.update(TV_HOST=HOST, HOME=str(self.home), FAKE_TV_STATE=str(self.state_path),
                        FAKE_TV_BIN=str(self.bin), FAKE_TV_UNDO_LOG=str(self.log))
        full_env.update(env or {})
        result = subprocess.run(
            ["bash", str(self.script), *args],
            cwd=self.cwd, env=full_env, capture_output=True, text=True, timeout=180,
        )
        with open(self.state_path) as f:
            return result, json.load(f)

    def writes(self, state):
        return [c for c in state["commands"] if c.startswith(("settings put", "settings delete"))]

    def assert_nothing_disabled(self, state):
        for call in state["calls"]:
            self.assertFalse(call[0] == "pm" and call[1:2] in (["disable-user"], ["disable"],
                                                                 ["uninstall"], ["clear"]), call)

    def test_show_reads_without_changing_anything(self):
        result, state = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        out = result.stdout
        self.assertRegex(out, r"screensaver_components\s+com\.gathin\.tvhome/\.ClockDream")
        self.assertRegex(out, r"screensaver_default_component\s+com\.google\.android\.apps\.tv\.dreamx/\.service\.Backdrop")
        self.assertRegex(out, r"screensaver_activate_on_dock\s+1")
        self.assertIn("600000 (10 min)", out)
        self.assertIn("com.gathin.tvhome/.ClockDream", out.split("can start now:")[1])
        self.assertRegex(out, r"com\.google\.android\.apps\.tv\.dreamx\s+disabled")
        self.assertRegex(out, r"com\.gathin\.tvhome\s+enabled")
        self.assertEqual(self.writes(state), [])
        self.assertFalse(any(c.startswith("pm enable") for c in state["commands"]))
        self.assertFalse(self.log.exists())

    def test_google_enables_ambient_mode_alone_then_writes_in_order(self):
        result, state = self.run_script("google", "--after", "5")
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = state["commands"]
        # Issued as a whole tv.py call of its own, nothing chained to it.
        enable = commands.index(f"pm enable --user 0 {GOOGLE_PKG}")
        self.assertEqual(self.writes(state), [
            f"settings put --user 0 secure screensaver_components {GOOGLE}",
            "settings put --user 0 secure screensaver_enabled 1",
            "settings put --user 0 system screen_off_timeout 300000",
        ])
        first_write = commands.index(self.writes(state)[0])
        self.assertLess(enable, first_write)
        secure = state["settings"]["secure"]
        self.assertEqual(secure["screensaver_components"], GOOGLE)
        self.assertEqual(state["settings"]["system"]["screen_off_timeout"], "300000")
        # The TV's own power-off timers are not this script's business.
        self.assertEqual(secure["sleep_timeout"], "7200000")
        self.assertEqual(secure["attentive_timeout"], "7200000")
        self.assertNotIn(GOOGLE_PKG, state["disabled"])
        self.assertIn("com.google.android.apps.tv.launcherx", state["disabled"])
        self.assert_nothing_disabled(state)
        self.assertIn("Done:", result.stdout)

    def test_undo_block_holds_the_previous_values_before_the_first_change(self):
        result, state = self.run_script("google", "--after", "5")
        self.assertEqual(result.returncode, 0, result.stderr)
        expected = [
            f"TV_HOST={HOST} tools/venv/bin/python -I tools/tv.py "
            "'settings put --user 0 secure screensaver_components com.gathin.tvhome/.ClockDream'",
            f"TV_HOST={HOST} tools/venv/bin/python -I tools/tv.py 'settings put --user 0 secure screensaver_enabled 1'",
            f"TV_HOST={HOST} tools/venv/bin/python -I tools/tv.py 'settings put --user 0 system screen_off_timeout 600000'",
            f"TV_HOST={HOST} tools/venv/bin/python -I tools/tv.py 'pm disable-user --user 0 {GOOGLE_PKG}'",
        ]
        log = self.log.read_text()
        for line in expected:
            self.assertIn(line, result.stdout.splitlines())
            self.assertIn(line, log.splitlines())
        self.assertRegex(log, r"# \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{4} before: tools/screensaver.sh google --after 5")
        # activate_on_sleep was already 1, so it is neither written nor in the undo.
        self.assertNotIn("activate_on_sleep", log)
        # On disk before anything was changed: the first change is the pm enable.
        enable = state["commands"].index(f"pm enable --user 0 {GOOGLE_PKG}")
        self.assertGreater(state["log_size"][enable], 0)
        self.assertEqual(state["log_size"][0], 0)
        # A second run appends instead of replacing the first undo.
        self.run_script("clock", "--after", "10")
        log = self.log.read_text()
        self.assertEqual(log.count("# Undo"), 2)
        self.assertIn("before: tools/screensaver.sh clock --after 10", log)

    def test_unset_activate_on_sleep_is_set_and_undone_with_delete(self):
        state = today()
        del state["settings"]["secure"]["screensaver_activate_on_sleep"]
        result, state = self.run_script("clock", state=state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.writes(state)[-1], "settings put --user 0 secure screensaver_activate_on_sleep 1")
        self.assertIn("'settings delete --user 0 secure screensaver_activate_on_sleep'", self.log.read_text())

    def test_clock_does_not_enable_anything(self):
        state = today()
        state["settings"]["secure"]["screensaver_components"] = "com.google.android.apps.tv.dreamx/.service.Backdrop"
        result, state = self.run_script("clock", "--after", "10", state=state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(c.startswith("pm enable") for c in state["commands"]))
        self.assertFalse(any(call[:2] == ["pm", "enable"] for call in state["calls"]))
        self.assertIn(GOOGLE_PKG, state["disabled"])
        self.assertEqual(self.writes(state), [
            f"settings put --user 0 secure screensaver_components {CLOCK}",
            "settings put --user 0 secure screensaver_enabled 1",
            "settings put --user 0 system screen_off_timeout 600000",
        ])
        self.assertNotIn("pm disable-user", self.log.read_text())
        self.assert_nothing_disabled(state)

    def test_component_with_shell_syntax_never_reaches_the_tv(self):
        for bad in ("com.foo/.Bar;reboot", "com.foo/.Bar $(reboot)", "com.foo/.Bar`reboot`",
                    "com.foo/.Bar'", "com.foo/.Bar&&reboot", "com.foo/.Bar|sh", "com.foo/.Outer$Inner",
                    "com.foo/.Bar\nreboot", "com.foo/.Bar,com.evil/.X", "com.foo", "/.Bar",
                    "foo/.Bar", "com.foo/", "com.foo/.", "com.foo/.Bar/.Baz", "-rf"):
            with self.subTest(component=bad):
                result, state = self.run_script(bad)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(state["commands"], [])
                self.assertEqual(state["calls"], [])
        for after in ("0", "121", "5m", "-1", "1e3", "", "5;reboot", "0x10", "1000"):
            with self.subTest(after=after):
                result, state = self.run_script("clock", "--after", after)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("whole minutes", result.stderr)
                self.assertEqual(state["commands"], [])
        result, state = self.run_script("--test")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(state["commands"], [])

    def test_explicit_component_in_either_form_is_accepted(self):
        for component in ("com.google.android.apps.tv.dreamx/.service.Backdrop", GOOGLE):
            with self.subTest(component=component):
                result, state = self.run_script(component, "--after", "120")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f"settings put --user 0 secure screensaver_components {component}", state["commands"])
                self.assertEqual(state["settings"]["system"]["screen_off_timeout"], "7200000")

    def test_value_that_does_not_take_fails(self):
        state = today()
        state["stuck"] = ["screen_off_timeout"]
        result, state = self.run_script("google", "--after", "5", state=state)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("screen_off_timeout: wanted 300000, the TV says 600000", result.stderr)
        self.assertNotIn("Done:", result.stdout)

    def test_screensaver_the_tv_does_not_have_changes_nothing(self):
        result, state = self.run_script("com.example.dreams/.Missing")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not a screensaver on this TV", result.stderr)
        self.assertEqual(self.writes(state), [])
        self.assertFalse(self.log.exists())

    def test_enabled_package_without_its_screensaver_stops_before_writing(self):
        state = today()
        state["dreams"] = [CLOCK_FULL]
        result, state = self.run_script("google", state=state)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f"pm enable --user 0 {GOOGLE_PKG}", state["commands"])
        self.assertIn("does not list", result.stderr)
        self.assertEqual(self.writes(state), [])
        self.assertIn(f"'pm disable-user --user 0 {GOOGLE_PKG}'", self.log.read_text())
        self.assert_nothing_disabled(state)

    def test_test_starts_the_screensaver_and_puts_the_delay_back(self):
        for dream_format, shot in (("android12", None), ("record", "custom/shot.png")):
            with self.subTest(dream_format=dream_format):
                state = today()
                state["dream_format"] = dream_format
                env = {}
                if shot:
                    (self.cwd / "custom").mkdir(exist_ok=True)
                    env["SCREENSAVER_SHOT"] = shot
                result, state = self.run_script("google", "--after", "5", "--test", state=state, env=env)
                self.assertEqual(result.returncode, 0, result.stderr)
                commands = state["commands"]
                lowered = commands.index("settings put --user 0 system screen_off_timeout 15000")
                check = commands.index("sleep 25; dumpsys dreams")
                self.assertLess(lowered, check)
                # The wait happened on the TV.
                self.assertIn(["sleep", "25"], state["calls"])
                shot_cmd = commands.index("screencap -p /sdcard/cyt-screensaver.png")
                wake = len(commands) - 1 - commands[::-1].index("input keyevent KEYCODE_WAKEUP")
                self.assertLess(check, shot_cmd)
                self.assertLess(shot_cmd, wake)
                self.assertEqual(self.writes(state)[-1], "settings put --user 0 system screen_off_timeout 300000")
                self.assertEqual(state["settings"]["system"]["screen_off_timeout"], "300000")
                self.assertIn(GOOGLE, result.stdout.split("dumpsys dreams:")[1])
                png = self.cwd / (shot or f"screensaver-test-{HOST}.png")
                self.assertTrue(png.read_bytes().startswith(b"\x89PNG"))
                self.assertEqual(state["pulls"][-1][0], "/sdcard/cyt-screensaver.png")
                self.assertEqual(state["files"], [], "the screenshot is removed from the TV")
                self.assertIn("OK:", result.stdout)
                self.assertIn("Unable to load content", result.stdout)

    def test_test_that_finds_no_screensaver_fails_and_still_puts_the_delay_back(self):
        state = today()
        state["dream_starts"] = False
        result, state = self.run_script("clock", "--after", "7", "--test", state=state)
        self.assertEqual(result.returncode, 1)
        self.assertIn("was not running", result.stderr)
        self.assertIn("dumpsys dreams: mCurrentDreamName=null", result.stdout)
        self.assertEqual(state["settings"]["system"]["screen_off_timeout"], "420000")
        self.assertEqual(self.writes(state)[-1], "settings put --user 0 system screen_off_timeout 420000")

    def test_failed_write_stops_before_the_test(self):
        state = today()
        state["settings"]["secure"]["screensaver_components"] = GOOGLE
        state["stuck"] = ["screensaver_components"]
        result, state = self.run_script("clock", "--test", state=state)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(f"screensaver_components: wanted {CLOCK}, the TV says {GOOGLE}", result.stderr)
        self.assertNotIn("sleep 25; dumpsys dreams", state["commands"])
        self.assertNotIn("settings put --user 0 system screen_off_timeout 15000", state["commands"])

    def test_test_stops_when_the_idle_time_does_not_go_down(self):
        state = today()
        state["ignored_puts"] = [["screen_off_timeout", "15000"]]
        result, state = self.run_script("google", "--after", "5", "--test", state=state)
        self.assertEqual(result.returncode, 1)
        self.assertIn("could not lower the idle time", result.stderr)
        # Stopped before the wait, instead of blaming the TV for a screensaver that did not start.
        self.assertNotIn("sleep 25; dumpsys dreams", state["commands"])
        self.assertNotIn("was not running", result.stderr)
        self.assertEqual(state["settings"]["system"]["screen_off_timeout"], "300000")

    def test_screenshot_path_that_is_a_folder_is_refused_before_the_tv_is_touched(self):
        (self.cwd / "shots").mkdir()
        result, state = self.run_script("google", "--test", env={"SCREENSAVER_SHOT": "shots"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("is a folder", result.stderr)
        self.assertEqual(state["commands"], [])

    def test_screenshot_that_cannot_be_saved_does_not_stop_the_test(self):
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            self.skipTest("root can replace files in a read-only folder")
        folder = self.cwd / "read-only"
        folder.mkdir()
        old = folder / "shot.png"
        old.write_bytes(b"an older picture")
        folder.chmod(0o555)
        self.addCleanup(folder.chmod, 0o755)
        result, state = self.run_script("google", "--test", env={"SCREENSAVER_SHOT": "read-only/shot.png"})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OK:", result.stdout)
        self.assertIn("could not replace", result.stderr)
        # The older picture is not reported as this test's screenshot.
        self.assertNotRegex(result.stdout, r"(?m)^  screenshot: ")
        self.assertEqual(old.read_bytes(), b"an older picture")
        commands = state["commands"]
        self.assertLess(commands.index("screencap -p /sdcard/cyt-screensaver.png"),
                        len(commands) - 1 - commands[::-1].index("input keyevent KEYCODE_WAKEUP"))
        self.assertEqual(state["files"], [], "the screenshot is removed from the TV")
        self.assertEqual(state["settings"]["system"]["screen_off_timeout"], "300000")

    def test_every_command_goes_to_user_0_whichever_profile_is_in_front(self):
        state = today()
        state["current_user"] = 10
        result, state = self.run_script("google", "--after", "5", state=state)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state["settings"]["secure"]["screensaver_components"], GOOGLE)
        self.assertEqual(state["settings"]["system"]["screen_off_timeout"], "300000")
        self.assertNotIn(GOOGLE_PKG, state["disabled"])
        self.assertEqual(state["profile_settings"], profile_settings(), "the other profile is left alone")
        for call in state["calls"]:
            if call[0] in ("settings", "pm", "cmd"):
                self.assertIn("--user", call)
                self.assertEqual(call[call.index("--user") + 1], "0", call)
        undo = [line for line in self.log.read_text().splitlines() if "tools/tv.py" in line]
        self.assertTrue(undo)
        for line in undo:
            self.assertIn("--user 0 ", line)

    def test_tv_that_does_not_answer_is_reported(self):
        state = today()
        state["offline"] = True
        result, state = self.run_script("google", state=state)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("could not read the settings", result.stderr)
        self.assertFalse(self.log.exists())

    def test_tv_host_is_required(self):
        state_env = {k: v for k, v in os.environ.items() if k != "TV_HOST"}
        self.state_path.write_text(json.dumps(today()))
        result = subprocess.run(["bash", str(self.script)],
                                cwd=self.cwd, env=state_env, capture_output=True, text=True, timeout=60)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("set TV_HOST", result.stderr)


if __name__ == "__main__":
    unittest.main()
