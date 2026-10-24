# Home Lights

A small Android TV app that switches the drawing-room lights from the TV remote. The lights listen
for Bluetooth LE advertisements, so the TV's own Bluetooth radio sends the same packets that nRF
Connect's Advertiser sends from a phone. No hub, no network, no account.

The drawing room has three rows of ceiling lights, numbered 1 to 3 as on the light remote, and the
remote also addresses them all at once. The screen shows a row per group, **All rows** first, with
a button for every command whose packets are known: **On**, **Off**, **Dimmer**, **Brighter**,
**Warmer**, **Cooler**. Every packet came from the light remote itself, recorded by the TV with
**Learn light remote** (see below) and turned into `lights.json` by `capture_to_lights.py`. New
lights and new packets go into that JSON file, pushed to the TV without a rebuild.

Round the ceiling runs an ambient strip, the cove, with a remote of its own: the "TC" touch
remote, with O and I at the top, a ring from white to yellow for the colour temperature and two
bulbs for the brightness. It has its own row, **Cove**, filled the same way by **Learn cove
remote**; until that capture is made the row shows no buttons. Whether the TV can hear this remote
at all is the first thing the capture tells (see [Learning the cove remote](#learning-the-cove-remote)).

## How the lights are driven

A Bluetooth LE advertisement is up to 31 bytes of records. The lights accept a command in either of
two records, which nRF Connect calls by these names:

| nRF Connect record | AD type | Identifier | Payload | On air |
|---|---|---|---|---|
| Service Data, UUID `0x08F0` ("format B") | `0x16` | 2 bytes | 24 bytes | 28 bytes |
| Manufacturer Data, Company ID `0x5556` ("format A") | `0xFF` | 2 bytes | 27 bytes | 31 bytes |

The app sends one record per advertisement, with no device name and no TX power. One more thing
rides in front of it: the 3-byte **Flags** record (`02 01 02`), which Android puts at the start of
every *connectable* advertisement. A phone app, and nRF Connect with its default options, send
connectable, so the lights have only ever seen `02 01 02` followed by the record. Format B is sized
for exactly that: 3 + 28 = 31 bytes. The app therefore sends format B connectable too. Format A
fills the 31 bytes on its own, so it has no room for Flags and goes out non-connectable, which is
also why nRF Connect could not send it connectable.

Each packet is advertised at the fastest interval Android offers (about every 100 ms) for
`burst_ms` (1.5 s by default, roughly 15 copies), then advertising stops.

The same bytes keep working on replay, so the packets are fixed strings in `lights.json`.

## Install

From the repository root, on a computer on the same Wi-Fi as the TV, with USB debugging on
(see [`PROMPT.md`](../PROMPT.md) for the TV side and `tools/` for the venv):

```
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt   # once
TV_HOST=192.168.1.26 lights/build-and-install.sh
```

Windows: `lights\build-and-install.ps1 -TvHost 192.168.1.26`.

The script builds the APK, pushes and installs it, grants the Bluetooth advertising permission over
adb, and starts the app. Nothing else on the TV changes.

The build needs a JDK 17 to 22 and the Android SDK. Android Studio provides both (its bundled
runtime is found automatically); without it, `brew install openjdk@17` and `brew install --cask
android-commandlinetools` followed by `sdkmanager "platforms;android-34" "build-tools;34.0.0"`.
The script prints which JDK and SDK it uses, or says what is missing: a newer Java such as 25
cannot run Gradle 8.9 and is skipped. `JAVA_HOME` and `ANDROID_HOME` override the search.

The app appears in the TV's app list and as a tile in the cleanyourtv launcher. The launcher also
offers it as a [module card](../docs/modules.md) ("Lights · Drawing room") the first time; answer
Show to keep it ahead of the grid.

## Use

Open Home Lights, read the status line at the bottom, then press **On** or **Off** on the TV
remote. Left and Right move between the buttons of a row, Up and Down between rows; Up from the
first row reaches **Learn light remote** and **Learn cove remote**. A row without packets says so
instead of showing buttons.

The remote's logic carries over: the row you are on is the row you adjust. ON 1 on the light remote
and then the brightness keys change row 1 only; on the TV, the Brighter button in row 1 does the
same, once its packets are captured. All rows adjusts every row together.

| Status line | Meaning |
|---|---|
| Ready. Bluetooth LE advertising available (Android 12, multi-advertising no, extended yes) | Press On or Off. "multi-advertising no" only means one packet at a time, which is all the app needs. |
| Sent Drawing room On. | The packet went on air. If the lights did not react, see Troubleshooting. |
| Press On or Off, then allow Nearby devices… | Android 12+ runtime permission. The install script grants it; otherwise the TV shows a dialog on the first press. |
| Bluetooth is off. Press On or Off to switch it on. | The first press asks Android to turn Bluetooth on. |
| This TV's Bluetooth cannot send advertisements… | The radio has no advertiser role; see Troubleshooting. |
| lights.json on the TV ignored (…); using the built-in list. | The pushed file has a mistake; the message says where. |

### Scripted check from the computer

No remote needed, and the same packet can be sent again and again:

```
TV_HOST=192.168.1.26 tools/venv/bin/python -I tools/tv.py 'am start -n tv.homelights/.MainActivity -e light all -e command on'
TV_HOST=192.168.1.26 tools/venv/bin/python -I tools/tv.py 'am start -n tv.homelights/.MainActivity -e light all -e command off'
TV_HOST=192.168.1.26 tools/venv/bin/python -I tools/tv.py 'logcat -d -s HomeLights'
```

`light` is the `id` from `lights.json`, `command` one of its command names. The log shows every
packet as it goes on air (`advertising service data 08F0, 24 bytes, with flags for 1500 ms: …`)
and the outcome.

## Changing the packets: lights.json

The built-in list is [`app/src/main/assets/lights.json`](app/src/main/assets/lights.json):

```json
{
  "burst_ms": 1500,
  "connectable": true,
  "lights": [
    {
      "id": "all",
      "name": "All rows",
      "commands": {
        "on":  [ { "service_data": "08F0", "data": "1000B559C42541FDEF78E0E567050CC0555F4A1AE719D07D" } ],
        "off": [ { "service_data": "08F0", "data": "1000EA2D71611DCBAA79F270FE6376E486E88FD664E3AA48" } ]
      }
    },
    { "id": "row-1", "name": "Row 1, over the sofas" },
    { "id": "row-2", "name": "Row 2, fan and pendant" },
    { "id": "row-3", "name": "Row 3, towards the dining table" },
    { "id": "cove", "name": "Cove, the ambient strip round the ceiling", "in_all": false }
  ]
}
```

- `burst_ms`: how long each packet is advertised, 100 to 60000. Optional, 1500 by default.
- `connectable`: send with the Flags record in front, as a phone does (see above). Optional, true by
  default; a packet can set its own `"connectable": false`. A payload over 24 bytes never gets Flags,
  there is no room, so format A is unaffected either way.
- `lights`: one entry per light or group, shown as a row in that order. `id` is what the scripted
  check names; `name` is the row's label (defaults to the id).
- `in_all`: whether **All rows** switches this light too, so that the screen marks it along with All
  rows. True unless given; false for the cove, which has a remote of its own.
- `commands`: the row's buttons. `on`, `off`, `dimmer`, `brighter`, `warmer` and `cooler` have
  fixed places and labels; any other name (lower-case letters, digits, hyphens, such as `moon` or
  `rgb`) gets a button after them, alphabetically. A row with no `commands` shows none, with a
  hint instead. A command is a list of packets, advertised one after another, or
  `{ "variants": [ [packets], [packets], ... ] }`, one list per press recorded from the remote: each
  press carries its own counter and the lights ignore a repeat of the counter they just saw, so the
  app sends the variants in turn. A packet is an object with `data` (the payload, hex; spaces,
  colons and a `0x` prefix are fine), optionally `burst_ms` and `connectable` of its own, and
  exactly one of:
  - `"service_data": "08F0"`: the 16-bit service UUID, as in nRF Connect's Service Data record;
  - `"manufacturer_data": "5556"`: the company ID, as in nRF Connect's Manufacturer Data record.

  The payload may be at most 27 bytes (31 with the record header). Format A as a second packet
  would be `{ "manufacturer_data": "5556", "data": "188752B65F2B5E00FC3151D0699F0824CBA5FC16C46CB22F9F6057" }`.

To try new packets without a rebuild, copy the file, edit it, and push it:

```
TV_HOST=192.168.1.26 lights/push-config.sh my-lights.json      # the app restarts and reads this file
TV_HOST=192.168.1.26 lights/push-config.sh --reset             # back to the built-in list
```

The pushed file lives at `/sdcard/Android/data/tv.homelights/files/lights.json`. The status line
says "Using lights.json pushed to the TV" while it is in use. A file with a mistake is ignored with
the reason on the status line, and the built-in list stays in force. Once a packet is proven, put it
in the built-in file and run `build-and-install.sh` again so it survives a reinstall.

## Learning the light remote

The light remote sends the same kind of packets the app does, and the TV's Bluetooth can hear
them. **Learn light remote** on the main screen asks for one button at a time and files every
packet it hears under that button, so the recording needs no clock-watching. The steps, in order:

| Step | Light remote button | Presses | Why |
|---|---|---|---|
| 1, 2 | ALL ON, ALL OFF | 3 each | The packets the TV already sends; a check that the capture works |
| 3 to 10 | ON 1, OFF 1, ON 2, OFF 2, ON 3, OFF 3, ON 4, OFF 4 | 2 each | Each row's own on and off |
| 11 | ALL ON | 1 | Selects every row for the next eight |
| 12 to 15 | sun+, sun−, K+, K− | 3 each | Brightness and colour temperature for all rows |
| 16 to 19 | K (round, top left), moon, RGB, Aid | 2 each | These carry the selected group too, so while it is "all" |
| 20 to 24 | ON 1, then sun+, sun−, K+, K− | 1, then 3 each | The same for row 1 |
| 25 to 29 | ON 2, then the four | 1, then 3 each | Row 2 |
| 30 to 34 | ON 3, then the four | 1, then 3 each | Row 3 |

A few steps can be run on their own, for example the four extra buttons again after a change:

```
TV_HOST=192.168.1.26 tools/venv/bin/python -I tools/tv.py 'am start -n tv.homelights/.LearnActivity -e steps extras'
TV_HOST=192.168.1.26 lights/pull-capture.sh lights/capture-extras.json
```

`extras` is ALL ON, then K, moon, RGB, Aid; a comma-separated list of step ids works as well.

Never press **SETUP**: it pairs or unpairs the lights. Press the buttons about a second apart;
when the screen's count has gone up, press **Next step** on the TV remote. **Finish** or Back stops
early and keeps what is recorded. The capture is saved after every step as
`/sdcard/Android/data/tv.homelights/files/capture.json`. Fetch it:

```
TV_HOST=192.168.1.26 lights/pull-capture.sh        # writes lights/capture.json and prints a summary
```

Then turn it into the packet list and rebuild (or push the file). Several capture files merge,
later ones overriding the steps they contain:

```
python3 -I lights/capture_to_lights.py lights/capture.json lights/capture-extras.json lights/app/src/main/assets/lights.json
```

For each step the capture lists every distinct packet, how many copies came, when, its signal
strength and the sender's address, with the records separated (`02 01 02 | 1B 16 F0 08 …`).

### What the capture showed

The remote sends every command twice within a few hundredths of a second, as manufacturer data
(format A) from one address and as service data under `0x08F0` (format B) from another, both with
the Flags record `02 01 02` in front. Format B is the one the lights were seen to answer from the
TV, so `lights.json` uses it; each format B packet takes its meaning from the format A twin nearest
in time, which reads plainly. Format A, 27 bytes after the company id:

| Byte | Meaning |
|---|---|
| 0 to 10 | constant (`18 87 52 B6 5F 2B 5E 00 FC 31 51`), presumably the remote's identity |
| 11 | command: `D0` on, `50` off, `44` adjustment press, `5C` adjustment release, `1C` moon, `A0`/`20` RGB (alternates), `90`/`10` Aid (alternates) |
| 13 | group: `9F` all, `1F` row 1, `DF` row 2, `5F` row 3, `BF` row 4 |
| 17 | counter, different on every press |
| 18 | in a `44` packet, which adjustment: `FC` brighter, `7C` dimmer, `BC` K+, `3C` K− |
| 19 to 24 | checksum over the rest |
| 25, 26 | constant `60 57` |

So the row travels inside every adjustment packet, which is why each row on the screen has its
own Brighter and Dimmer; the K, moon, RGB and Aid buttons carry the selected group as well. An
adjustment press is two packets, press then release, kept together in `lights.json` (400 ms each).
K+ is filed as `cooler` and K− as `warmer`, the Kelvin reading, except for All rows, where the
drawing-room lights do the opposite (`SWAP_K` in `capture_to_lights.py`).

Two checks on the Learn screen: "Heard in this step" must go up when you press the light remote;
if it stays at 0 while "Other devices ignored" climbs, the remote's packets are in a format the
filter does not know. Open the screen with everything recorded, press one button, and send the
file: `am start -n tv.homelights/.LearnActivity -e filter none` over `tools/tv.py`.

If you prefer the phone, the same sequence works with nRF Connect's Scanner, noting the time of
each step; the TV route is simply less to keep track of.

## Learning the cove remote

The ambient strip round the ceiling has a remote of its own, the "TC" touch remote: O and I at the
top, a ring from white (left) to yellow (right) that sets the colour temperature, two bulbs for the
brightness. Nothing is known about its packets yet, not even whether they are Bluetooth LE at all:
remotes of this shape are often plain 2.4 GHz radio, which the TV cannot hear. **Learn cove
remote** on the main screen finds out, and records what there is:

| Step | On the cove remote | Presses | Why |
|---|---|---|---|
| 1 | nothing, for ten seconds | 0 | The TV notes what the room sends on its own (phones, fans, the neighbours' devices); those senders, and packets of the same shape (a phone's rotating-address packets keep their shape), are left out of every later step |
| 2, 3 | I, O | 3 each | On and off |
| 4, 5 | the upper bulb, the lower bulb | 3 each | Brighter, dimmer |
| 6 to 8 | the ring at its white end, at the bottom, at its yellow end | 2 taps each | Three colour temperatures: cool, neutral, warm |
| 9, 10 | the yellow dot, the white dot | 2 each | Whatever they do, note it |
| 11 | a slow slide round the ring, white end to yellow end | once | Shows how the ring encodes the colour; it becomes no button |

Unlike the light remote's run, every packet heard is recorded, not only the two formats the lights
use, since the format is the thing to find out; that is why the quiet first step matters. The check
is on the screen: from step 2 on, "Heard in this step" must go up with each press. If it stays at 0
while "Other devices ignored" climbs, the TV does not hear the remote: it is not a Bluetooth LE
remote, and driving the strip takes a bridge next to it (see Troubleshooting) rather than the TV's
radio. If it goes up, fetch the file and turn it into packets, together with the light remote's
captures:

```
TV_HOST=192.168.1.26 lights/pull-capture.sh --cove      # writes lights/capture-cove.json and prints a summary
python3 -I lights/capture_to_lights.py lights/capture.json lights/capture-extras.json lights/capture-cove.json lights/app/src/main/assets/lights.json
```

The cove steps are filed as heard, without decoding: each press's packets (those within half a
second of each other) become one variant of the command, so **On**, **Off**, **Brighter** and
**Dimmer** appear on the Cove row, then **Cool**, **Neutral**, **Warm**, **White dot** and
**Yellow dot**. Push the file to try them; the ones that work go into the built-in file with a
rebuild. The script prints, per step, how many presses it found and from which sender addresses,
which is where to look when a button does nothing: a step with packets from several senders may
have caught a passer-by's phone, and a packet in a record the app cannot send (anything but service
data or manufacturer data) is named on stderr. The slide step is reported, not filed.

The same run from the computer, and a few steps on their own:

```
TV_HOST=192.168.1.26 tools/venv/bin/python -I tools/tv.py 'am start -n tv.homelights/.LearnActivity -e steps cove'
TV_HOST=192.168.1.26 tools/venv/bin/python -I tools/tv.py 'am start -n tv.homelights/.LearnActivity -e steps cove-quiet,cove-on,cove-off'
```

A re-run should include `cove-quiet`, so the room's own packets are known; without it every packet
heard counts as the remote's, and the converter says so.

### What the capture of 2026-10-24 showed

Nothing from the remote. [`capture-cove.json`](capture-cove.json) has the quiet step hearing 21
distinct packets from 19 senders in 18 seconds, which shows the scanner working, and then every
button step hearing nothing new while 100 to 200 background packets per step were set aside. The
remote's red light, in the slot at its end, came on with every press, so it was transmitting
something; the TV's Bluetooth scan does not hear it. That scan covered legacy advertisements only,
as Android's default does; since then it also takes Bluetooth 5 extended advertisements and the
long-range PHY where the radio can (the status line says "Listening, for Bluetooth 5 extended
advertisements too"), which is the one way a Bluetooth LE remote could have hidden from it. A
short re-run, `-e steps cove-quiet,cove-on,cove-off`, settles that.

Failing that, this remote is not Bluetooth LE at all. Remotes of this shape are usually 2.4 GHz
proprietary radio on an nRF24-style chip (the Mi-Light family and many generic LED-strip remotes),
sometimes infrared through that slot at the end. Two checks tell which, at no cost:

- Cover the slot at the end of the remote with a hand and press I or O. If the strip still
  answers, it is radio; if it stops answering, it is infrared.
- The receiver in the cove, where the strip's power supply is, has a label: "2.4G" or "RF" against
  "IR", and often a model number to look up.

Either way the TV's own radio cannot reach the strip, and the way forward is a small bridge next to
the receiver (an ESP32 with an nRF24L01+ module for 2.4 GHz, or with an infrared LED for IR), driven
by the TV over Wi-Fi: the same bridge Troubleshooting describes for a TV whose Bluetooth cannot
advertise. Replacing the receiver with a Bluetooth LE one is the other route; then Learn cove remote
records its remote as it did the light remote's.

## Troubleshooting

- **"This TV's Bluetooth cannot send advertisements"**, or the log says `FEATURE_UNSUPPORTED`: the
  TV's Bluetooth controller has no advertiser (peripheral) role. Nothing in software changes that.
  The way forward is a small always-on bridge next to the lights (an ESP32 board, or an old phone)
  that advertises on the TV's behalf, with the TV talking to it over Wi-Fi.
- **"Sent" but the lights do nothing.** The TV accepted the packet; the question is whether the
  lights can hear it and recognise it. Two things to try, each a pushed file, no rebuild:
  1. The other Flags setting: `"connectable": false` at the top of the file (or `true` if it was
     false), then press again.
  2. Is the TV transmitting at all? Push a file with `"burst_ms": 20000`. On the phone open
     nRF Connect, choose the **Scanner** tab, press **SCAN**, and stand near the TV. Press **Off** on
     the TV. For the next 20 seconds a new entry appears in the phone's list; tap it to expand it.
     If one of its lines reads **Service Data** with **0x08F0**, the TV is transmitting the packet,
     and the remaining difference is in nRF Connect's Advertiser options for the packet that worked
     from the phone (Connectable, Legacy, interval): a screenshot of that options page shows what to
     match. If no such entry ever appears, the TV's Bluetooth reports success without radiating,
     which is a chipset problem; see the first point above for the bridge.
- **"no free advertiser slot"**: another app on the TV is advertising. Stop it (Settings › Apps) and
  press again.
- **Permission refused by mistake**: Settings › Apps › Home Lights › Permissions › Nearby devices, or
  run `build-and-install.sh` again, which grants it over adb.
- **The pushed file is ignored**: the status line names the mistake (a missing `id`, an odd number
  of hex digits, a payload over 27 bytes, both record kinds in one packet). `push-config.sh` refuses
  a file that is not JSON at all.

## Next steps

- **Generate packets on the TV.** Format A is nearly understood (the table above); format B and
  the checksum are what the encoder on the computer (`encode.py`, `mydecode.py`, `pk.txt`) knows.
  Copy those files into `lights/protocol/` and a later round ports the encoder to Java, so every
  command is built in the app with a fresh counter instead of rotating recorded presses.
- **The extra buttons.** `extras` holds K, moon, RGB and Aid as recorded; what they do is for a
  test on the lights.
- **The cove remote.** Once its capture is in and the buttons work, decode it the way the light
  remote's was: a counter, a command byte, and whether the ring sends an absolute colour
  temperature, which would give the Cove row a Warmer and a Cooler as well.
- **The room.** A drawn plan of the drawing room with each light as a focusable spot. A flat or
  isometric drawing on a Canvas suits a 1 GB TV; a 3D engine does not.
- **Colour and brightness**, once those packets exist, as extra commands per light.

## Development

```
cd lights && ./gradlew testReleaseUnitTest lintRelease assembleRelease
```

The JVM tests cover the hex codec, the record layout (format A is exactly 31 bytes, format B 28),
the `lights.json` parser including the built-in file, the learn-step lists of both remotes, the raw
advertisement reader and the capture file with its quiet-step baseline. Everything that sends is in `Advertiser.java`, everything that listens
in `LearnActivity.java`; the main screen is `MainActivity.java`. CI builds this project on every
pull request.
