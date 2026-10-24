# Home Lights

A small Android TV app that switches the drawing-room lights from the TV remote. The lights listen
for Bluetooth LE advertisements, so the TV's own Bluetooth radio sends the same packets that nRF
Connect's Advertiser sends from a phone. No hub, no network, no account.

This is the first round: one row, **Drawing room**, with **On** and **Off**. New lights and new
packets go into a JSON file that is pushed to the TV without a rebuild.

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

Open Home Lights, read the status line at the bottom, then press **On** or **Off** on the remote.
Left and Right move between On and Off, Up and Down between lights.

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
TV_HOST=192.168.1.26 tools/venv/bin/python -I tools/tv.py 'am start -n tv.homelights/.MainActivity -e light drawing-room -e state on'
TV_HOST=192.168.1.26 tools/venv/bin/python -I tools/tv.py 'am start -n tv.homelights/.MainActivity -e light drawing-room -e state off'
TV_HOST=192.168.1.26 tools/venv/bin/python -I tools/tv.py 'logcat -d -s HomeLights'
```

`light` is the `id` from `lights.json`, `state` is `on` or `off`. The log shows every packet as it
goes on air (`advertising service data 08F0, 24 bytes for 800 ms: 1B16F008...`) and the outcome.

## Changing the packets: lights.json

The built-in list is [`app/src/main/assets/lights.json`](app/src/main/assets/lights.json):

```json
{
  "burst_ms": 1500,
  "connectable": true,
  "lights": [
    {
      "id": "drawing-room",
      "name": "Drawing room",
      "on":  [ { "service_data": "08F0", "data": "1000B559C42541FDEF78E0E567050CC0555F4A1AE719D07D" } ],
      "off": [ { "service_data": "08F0", "data": "1000EA2D71611DCBAA79F270FE6376E486E88FD664E3AA48" } ]
    }
  ]
}
```

- `burst_ms`: how long each packet is advertised, 100 to 60000. Optional, 1500 by default.
- `connectable`: send with the Flags record in front, as a phone does (see above). Optional, true by
  default; a packet can set its own `"connectable": false`. A payload over 24 bytes never gets Flags,
  there is no room, so format A is unaffected either way.
- `lights`: one entry per light or group, shown as a row in that order. `id` is what the scripted
  check names; `name` is the row's label (defaults to the id).
- `on` and `off`: a list of packets, advertised one after another. A packet is an object with
  `data` (the payload, hex; spaces, colons and a `0x` prefix are fine) and exactly one of:
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

- **Generate packets on the TV.** The encoder (`encode.py`, `mydecode.py`, `pk.txt` and its README)
  lives outside this repository for now. Copy those files into `lights/protocol/` and the next round
  ports the encoder to Java, so On, Off and every later command are built in the app instead of
  pasted into JSON.
- **More lights.** One entry per light or group in `lights.json`; the screen lists them.
- **The room.** A drawn plan of the drawing room with each light as a focusable spot. A flat or
  isometric drawing on a Canvas suits a 1 GB TV; a 3D engine does not.
- **Colour and brightness**, once those packets exist, as extra commands per light.

## Development

```
cd lights && ./gradlew testReleaseUnitTest lintRelease assembleRelease
```

The JVM tests cover the hex codec, the record layout (format A is exactly 31 bytes, format B 28) and
the `lights.json` parser, including the built-in file. Everything that touches Bluetooth is in
`Advertiser.java`; the screen is `MainActivity.java`. CI builds this project on every pull request.
