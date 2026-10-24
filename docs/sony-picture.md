# Sony OLED picture without Vivid

On a 2023 Sony BRAVIA XR OLED (the A80L family) every picture mode but Vivid can look dark in a
bright living room, so the TV ends up in Vivid for good, with its cool whites, pushed colour and
contrast that moves with the scene. Much of that darkness comes from settings that can be switched
off; the rest is panel protection that cannot. This page lists what dims the picture, the profile
we use instead, how it is applied and undone, and what we left alone on purpose.

In short: **Custom** for SDR and HDR10, **Dolby Vision Bright** for Dolby Vision; the Ambient light
sensor (whose three automatic adjustments were all on in Custom), Auto picture mode, power saving and
the apps' calibrated modes off; Brightness Max and Peak
luminance High, as in Vivid; and the extras Vivid adds (Black adjust, Adv. contrast enhancer, Live
Colour) off. Most values are starting points from reviews and Sony's own guidance, not a calibration
of one particular panel. SDR Brightness Max and Peak luminance High are our own bright-room choice,
to match Vivid; the more cautious daytime SDR start is Brightness 35 with Peak luminance Medium.

It was written on an XR-83A80L (Google TV 12, India). Names are as that TV shows them, British
spelling included. Sony warns that wording and availability depend on the model, the region and
the signal on screen ([help guide](https://helpguide.sony.net/tv/gusltn2/v1/en-003/01-03-09_01.html)),
so an HDR-only or app-only item may simply not be there.

## The Picture menu on this TV

Settings → Display & Sound → Picture. Over ADB, `am start -a android.settings.DISPLAY_SETTINGS`
opens it; on this TV that resolves to `com.sony.dtv.configsettings/.PictureSettingActivity`.

| Section | Items |
|---|---|
| Basic | Picture mode, Reset picture settings for *mode*, Auto picture mode |
| Ambient Optimisation Pro | Ambient light sensor, with Auto luminance level, Auto tone curve and Auto white balance under it; Auto adjust picture and sound (uses the camera, greyed out here) |
| Brightness | Brightness (0 to Max, which is 50), Contrast, Gamma, HDR tone mapping (greyed out for SDR), Black level, Black adjust, Adv. contrast enhancer, Peak luminance |
| Colour | Colour, Hue, Colour temperature, Live Colour |
| Clarity | Sharpness, Reality Creation, Random noise reduction, Digital noise reduction, Smooth gradation |
| Motion | Motionflow, Film mode |
| Video signal | HDR mode, HDMI video range, Colour space |
| Adv. colour adjustment | White balance and per-colour controls |

An app's calibrated mode appears under Basic only while that app is open.

## Where the TV keeps picture settings

The Picture menu edits whichever memory is in use, and this TV has several. Sony's own service logs the
switch every time the app in front changes (`logcat`, `PictureModeController: changeState`):

| Memory | Used for | As found |
|---|---|---|
| Android world general | Home and other non-video apps | Vivid |
| Android world video | Video apps: YouTube, and any app whose calibrated mode is off | Calibrated |
| Android world partner | An app whose "*App* calibrated mode" is on (Prime Video here) | Calibrated |

Each memory remembers its own picture mode, and each picture mode keeps its own values for each kind of
signal (SDR, HDR10, Dolby Vision). So tuning Custom on the Home screen does nothing for Netflix, and a
profile has to be applied once per memory and per signal, with that app and that kind of content on
screen.

**Calibrated** is Sony's partner-app mode. An app's switch ("Prime Video calibrated mode", "Netflix
calibrated mode") turns it on, and while it is on the Picture mode row and the light sensor are greyed out.
It is not in the Picture mode list, so nobody can pick it there. The menu still shows a list entry, the
first one (Vivid), as a stand-in. The real mode is named in the row under it, "Reset picture settings for
Calibrated", and over REST as `pictureMode` `calibrated`. On this TV the video memory was in Calibrated
too, with no app switch on. Its values (Gamma −2, Peak luminance Medium) are part of why apps looked dark.

## Why the picture looks dark

Two kinds of thing dim this TV: settings, which can be switched off, and panel protections, which
cannot. The settings explain most of it, so check them first.

| Mechanism | Kind | What it does | As found | Our setting |
|---|---|---|---|---|
| Ambient light sensor (Ambient Optimisation Pro) | Setting | Lowers brightness as the room gets darker ([Sony](https://www.sony.com/electronics/support/articles/00190409), [help guide](https://helpguide.sony.net/tv/musltn1/v1/en-003/contents/TP1001675539.html)). Its sub-options say how: Auto luminance level, Auto tone curve ("easier to see dark areas in a bright room"), Auto white balance | Vivid: On, all three Auto sub-options Off, which is why Vivid never dimmed. Custom: On, all three On | Off |
| Power saving (System → Power & Energy) | Setting | Low and High cut light output; Sony recommends Off to make its 2023 and later TVs brighter ([Sony](https://www.sony.com/electronics/support/articles/00283314)) | Off | Off |
| Auto picture mode | Setting | Switches mode by content, on HDMI inputs ([Sony](https://www.sony.com/electronics/support/articles/00190409)) | Off | Off |
| Netflix calibrated mode, and its adaptive version | App setting | Netflix's mode replaces yours and the other modes grey out while it is on ([Sony](https://www.sony.com/electronics/support/articles/00201344)); the adaptive version also follows room light ([Sony](https://www.sony-asia.com/bravia/smart-tv-features)) | On: Netflix played Dolby Vision in Calibrated | Off |
| Prime Video calibrated mode | App setting | Added in firmware 6.8100 ([Sony](https://www.sony.co.in/electronics/support/lcd-tvs-android-x90l_x91l-series/xr-75x90l/software/00363716)); puts Prime in Calibrated (Gamma −2, Peak luminance Medium on this TV) | On | Off |
| Sony Pictures Core Calibrated Mode | App setting | The same idea for Sony's own film app ([Sony](https://www.sony-asia.com/bravia/smart-tv-features)) | Not read yet | Off |
| Dolby Vision Dark | Picture mode | Dolby Vision brings its own three modes (REST `dvDark`, `dvBright`, `dvVivid`). Dark is the accurate one for a dark room, Bright the daylight one ([RTINGS](https://www.rtings.com/tv/reviews/sony/a80l-a80cl-oled/settings)) | Dolby Vision Vivid | Dolby Vision Bright |
| Motionflow Clearness | Setting | Higher settings insert black frames to cut blur, and that cuts light output ([Sony](https://www.sony.com/electronics/support/articles/00190409), [RTINGS](https://www.rtings.com/tv/reviews/sony/a80l-a80cl-oled/settings), [AVForums](https://www.avforums.com/reviews/sony-a84l-a80l-xr-65a84l-bravia-xr-oled-tv-review.20826/)). This panel offers only Low and High | Low | Low |
| Peak luminance | Setting | Off, Low, Medium, High: how far bright areas are pushed; High gives the most ([Sony](https://www.sony.com/electronics/support/articles/00190409)) | Vivid High; Custom and Calibrated Medium | High |
| Gamma | Setting | Below 0 darkens the mid-tones | Vivid 0; Custom and Calibrated −2 | 0 |
| Automatic Brightness Limiter (ABL) | Panel protection | A large bright area gets far less light than a small highlight. AVForums measured 623 nits on a 10% window and 133 nits full screen on its sample ([AVForums](https://www.avforums.com/reviews/sony-a84l-a80l-xr-65a84l-bravia-xr-oled-tv-review.20826/)); RTINGS sees the strongest limiting when most of the screen is bright ([RTINGS](https://www.rtings.com/tv/reviews/sony/a80l-a80cl-oled)) | Always on | No switch. Peak luminance High leaves the most headroom but does not remove it |
| Static-image and temperature dimming | Panel protection | A still picture, home and app top screens included, dims slowly and recovers when the picture changes. Sony calls this normal and only offers Peak luminance Medium or Low to make the swing smaller ([Sony](https://www.sony-asia.com/electronics/support/articles/00173465)) | Always on | No switch. We keep High and accept the swing |

Two notes on the protections. A launcher home screen is a still picture too, so Home dimming after
a while is this, not a fault. And not every dark scene is protection: HDTVTest saw no creeping
darkening in long dark HDR scenes on its A80L ([HDTVTest](https://www.youtube.com/watch?v=H6rIq9vVcPw)).

To tell them apart, watch the same scene with the sensor, the calibrated modes and power saving off.
Darkness that follows a whole bright scene is ABL. Darkness that creeps in on a still picture and
lifts when it changes is static-image dimming. Darkness that starts the moment a title switches to
Dolby Vision is the Dolby Vision mode.

## The profile we use

The light output stays where Vivid had it (Brightness Max, Peak luminance High); what goes is the
processing that makes Vivid inaccurate. The TV only offers the modes and items of the signal on
screen, so each column is set with that kind of content playing, and items the TV greys out for a
signal are skipped, not forced.

| Setting | SDR: Custom | HDR10: Custom | Dolby Vision: Dolby Vision Bright | Vivid as found |
|---|---|---|---|---|
| Auto picture mode | Off | Off | Off | Off |
| Ambient light sensor | Off | Off | Off | On |
| Auto luminance level, Auto tone curve, Auto white balance | Off | Off | Off | Off |
| Brightness | Max (50) | Max (50) | Max (50) | 50 |
| Contrast | 90 | 90 | 90 | 100 |
| Gamma | 0 | 0 | 0 | 0 |
| HDR tone mapping | greyed out | Brightness preferred | left to Dolby Vision | greyed out |
| Black level | 50 | 50 | 50 | 50 |
| Black adjust | Off | Off | Off | High |
| Adv. contrast enhancer | Off | Off | Off | High |
| Peak luminance | High | High | High | High |
| Colour | 50 | 50 | 50 | 60 |
| Hue | 0 | 0 | 0 | 0 |
| Colour temperature | Expert 1 | Expert 1 | Expert 1 | Cool |
| Live Colour | Off | Off | Off | High |
| Sharpness | 50 | 50 | 50 | 75 |
| Reality Creation | Auto | Auto | Auto | Auto (Custom had Manual, Resolution 20) |
| Random and Digital noise reduction | Off | Off | Off | Auto |
| Smooth gradation | Low | Low | Low | Low |
| Motionflow | Custom: Smoothness 1, Clearness Low | same | same | Auto |
| Film mode | High | High | High | High |
| HDR mode, HDMI video range, Colour space | Auto | Auto | Auto | Auto (HDMI video range greyed out for apps) |
| Adv. colour adjustment | untouched | untouched | untouched | not read |

Outside the Picture menu: Power saving Off (it already was) and Pixel shift On (Expert panel
settings, set by hand). Vivid was read on 22 October 2026; REST gave the same values in SDR and in
Dolby Vision Vivid. In both readings Auto picture mode was Off and not adjustable over REST (Sony
says it works on HDMI inputs); HDR mode, Colour space and the light sensor were unavailable only in
Dolby Vision Vivid.

Why these values:

- **Contrast 90, Gamma 0, Black level 50, Expert 1** are RTINGS' SDR settings, and RTINGS also turns
  off the light sensor, Auto picture mode and power saving
  ([RTINGS](https://www.rtings.com/tv/reviews/sony/a80l-a80cl-oled/settings)).
- **Brightness** is the panel's light output, 0 to 50; it is not the black level, which is Black
  level ([Sony](https://www.sony.com/electronics/support/articles/00190409)). RTINGS sets 20 only to
  hit 100 cd/m² for a dark-room calibration and says that is too dim for most rooms. A more cautious
  daytime SDR start is 35 with Peak luminance Medium, raising Brightness toward 50 before trying
  High. We start at Max because the comparison is Vivid, which was at 50. For evening viewing turn
  Brightness down, toward 20, rather than touching Black level.
- **Peak luminance High** everywhere for daylight. Owners on AVForums tend to use Medium for SDR and
  High for HDR and Dolby Vision ([AVForums](https://www.avforums.com/threads/sony-a84l-a80l-xr-65a84l-bravia-xr-oled-tv-review-comments.2457978/page-2));
  Medium is the step down if the static-image swing bothers anyone.
- **Black adjust, Adv. contrast enhancer, Live Colour Off.** The first two change dark areas and
  contrast scene by scene, and Live Colour pushes saturation
  ([Sony](https://www.sony.com/electronics/support/articles/00190409)). All three were on High in
  Vivid.
- **Sharpness 50** is Sony's reference position; RTINGS uses 55.
- **Reality Creation Auto** is left as it was. RTINGS uses Manual 20 for lower-resolution sources,
  AVForums turned it Off to measure.
- **Noise reductions Off** suit clean streams; raise them for a noisy source.
- **Smooth gradation Low** hides banding in imperfect material such as streams, as HDTVTest
  suggests ([HDTVTest](https://www.youtube.com/watch?v=H6rIq9vVcPw)).
- **Motionflow Custom, Smoothness 1, Clearness Low.** Clearness Low, the lower of the two settings
  this panel offers, keeps the light; Smoothness 1 is the mild smoothing HDTVTest and AVForums suggest
  for juddery pans, and Min leaves film motion untouched.
- **Film mode High** recovers 24 fps motion from apps that play at 60 Hz. Low is better for 50 Hz
  broadcast with mixed edits if it glitches (HDTVTest).
- **HDR mode, HDMI video range, Colour space Auto.** Forcing the wrong one makes the picture look
  wrong; a range mismatch crushes shadows or washes out blacks
  ([help guide](https://helpguide.sony.net/tv/gusltn2/v1/en-003/01-03-09_01.html)).
- **Adv. colour adjustment untouched.** Its white balance and per-colour controls need a meter.

### The accuracy trade-offs

**HDR tone mapping: Brightness preferred, not Gradation preferred.** Gradation preferred keeps
detail in bright gradations and is RTINGS' HDR choice. Brightness preferred lifts the whole picture
and gives some of that highlight detail away. HDTVTest found the A80L's conservative HDR mapping can
leave demanding scenes darker on average while keeping the bright detail, and AVForums notes the
same lean towards highlights over punch. The complaint here is HDR looking dark in daylight, so
HDR10 gets Brightness preferred. Gradation preferred is one menu change away, worth making for a film
night. Dolby Vision does its own mapping, so we leave the item alone there.

**Colour temperature: Expert 1, with Neutral as the fallback.** Expert 1 is the reference-oriented
white for SDR; RTINGS uses Expert 2 for HDR. Next to Vivid's Cool, Expert 1 looks warm at first.
If it does not settle for the people watching, Neutral (REST `neutral`) is one change away: it looks
whiter because it is cooler, not because it is more accurate. We copy no one's white-balance
numbers; a calibration belongs to one panel.

**Dolby Vision Bright, not Dark.** RTINGS calls Dark the most accurate, and it is the right choice
for a dark room. Bright is the daylight compromise that still avoids Vivid.

## How it is applied

The ADB shell cannot write Sony's picture settings directly: Sony's settings providers are either
not exported or need a signature permission to write. Two paths work, and both record the value they
find before the first change, so running a profile twice never records the profile as the "before".

**The Picture menu itself, through `tools/sonyui.py`.** This is the path used on the reference TV, and
it reaches every row: it opens the Picture menu with the `am start` above, moves with D-pad keys, and
reads a `uiautomator` dump after every key to check which row has focus before the next one. Center is
only pressed on a folded section header. It needs nothing but ADB, so no pairing.

Each run works on the memory in front of it, so open the app (or Home) first and put the kind of
content you are tuning on screen, then:

```bash
export TV_HOST=192.168.1.50
tools/venv/bin/python -I tools/sonyui.py read                 # every row and its value, changes nothing
tools/venv/bin/python -I tools/sonyui.py set 'Basic/Prime Video calibrated mode=Off'   # with Prime Video open
tools/venv/bin/python -I tools/sonyui.py set 'Basic/Picture mode=Custom' \
  'Ambient Optimisation Pro/Ambient light sensor=Off' 'Brightness/Gamma=0' 'Brightness/Peak luminance=High' \
  'Clarity/Reality Creation=Auto' 'Motion/Motionflow/Smoothness=1'
tools/venv/bin/python -I tools/sonyui.py set 'Brightness/HDR tone mapping=Brightness preferred'   # HDR10 playing
```

`set` changes rows in the order the TV lists them. A Picture mode step is a precondition: if the mode
cannot be selected (an app's calibrated mode greys the row out, or the mode is not in the list), nothing
after it is changed. Turn an app's calibrated mode off in a run of its own, then tune. Rows the TV greys
out for the signal on screen are skipped and reported.

Every run records under a label, by default the app in front plus `sdr` or `dv` (for example
`com.netflix.ninja-dv`), and refuses to reuse a label over another app or signal. The tool cannot tell
HDR10 from SDR, so name HDR10 runs yourself, e.g. `--label com.google.android.youtube.tv-hdr10`.

**The TV's REST interface, through `tools/bravia.py`.** `http://<tv>/sony/video` answers
`getPictureQualitySettings` without authentication, and `bravia.py status` prints every target for the
signal on screen. Writes need a pairing or a pre-shared key. Pairing is done by a person at the TV: the
tool asks to register, the TV shows a 4-digit PIN, and they type it in. The pairing lasts about two
weeks. A pre-shared key set under IP control in the TV's network settings works instead, with nothing
to renew. What REST reaches on this TV:

| REST target | Menu item | Values on this TV |
|---|---|---|
| `pictureMode` | Picture mode | SDR: `vivid`, `standard`, `cinema`, `game`, `graphics`, `photo`, `custom`; Dolby Vision: `dvDark`, `dvBright`, `dvVivid` |
| `brightness`, `contrast`, `color`, `hue`, `sharpness` | Brightness, Contrast, Colour, Hue, Sharpness | numbers; Brightness 0 to 50 |
| `colorTemperature` | Colour temperature | `cool`, `neutral`, `warm`, `expert1`, `expert2` |
| `colorSpace`, `hdrMode` | Colour space, HDR mode | `auto` and the forced options |
| `lightSensor` | Ambient light sensor, nominally | `on`, `off`. On this firmware it does not follow the menu: it still read `on` after the menu's Ambient light sensor was switched off, so check the menu, not REST |
| `autoPictureMode` | Auto picture mode | `auto`, `auto24p`, `off`; not adjustable in either reading, already `off` |
| `xtendedDynamicRange` | Peak luminance | `off`, `low`, `medium`, `high` |
| `autoLocalDimming` | none on this OLED | left alone |

Power saving is in the `system` service (`getPowerSavingMode`, `setPowerSavingMode`).

`bravia.py picture apply` switches to Custom (Dolby Vision Bright for Dolby Vision) and writes the
light sensor, Auto picture mode, Brightness, Contrast, Peak luminance, HDR mode and Colour space, then
Power saving. Given what we found, prefer the menu: REST's `lightSensor` does not track the menu's
switch on this firmware, REST cannot reach Gamma, the calibrated-mode switches or HDR tone mapping, and
it cannot see an app's Calibrated mode except as the value `calibrated`.

Pixel shift is not in the Picture menu and neither tool changes it. It is under Display & Sound →
Expert panel settings, and it was already On.

### Undoing it

`tools/sonyui.py restore --label L` puts one memory back. Open the same app (or Home) and play the same
kind of content as when the label was recorded; restore refuses another app, or Dolby Vision against
SDR, and cannot check HDR10 against SDR, so that part is on you. It switches to each recorded mode,
puts its rows back, returns to the mode that was active, then puts calibrated-mode switches back. Run
`tools/sonyui.py show-record` to see the labels, and `restore --label L --dry-run` to see the plan.

One thing it cannot undo: a memory found in Calibrated. Calibrated is not in the Picture mode list, so
restore reports that the mode was not reached and leaves the memory in the mode it was tuned in, with
that mode's rows put back. For an app with a calibrated-mode switch, putting the switch back on brings
Calibrated back for that app; the video-app memory has no such switch.

`tools/bravia.py picture restore` undoes its own REST runs the same way, with a valid pairing.

Without the tools: Picture → Basic → Reset picture settings for Custom returns Custom to Sony's
defaults ([help guide](https://helpguide.sony.net/tv/gusltn2/v1/en-003/01-03-09_01.html)), and an
app's calibrated mode can be switched back on under Basic with that app open. The profile does not
edit Vivid's values, so choosing Vivid again brings the old look back, apart from switches the TV keeps
across modes; turn the Ambient light sensor back on by hand if you want it.

## What we did not do

- **The service menu.** We found no verified key sequence or item list for these Google TV OLED
  sets, and what it holds is the factory setup. Changing it risks the factory calibration and the
  warranty, for no documented gain in brightness.
- **Panel refresh** (Expert panel settings). It is maintenance for visible image retention, not a
  brightness fix. Sony advises running it at most once a year, because overuse can affect the
  panel's life ([help guide](https://helpguide.sony.net/tv/kusltn2/v1/en-003/print.html)).
  Pixel shift stays On.
- **Hotel and Pro mode packages** (`com.sony.dtv.b2b.*`). They are for hotels and installers. In the
  package dump the hotel-mode service's receivers are for boot, scheduled restarts, power
  scheduling and time sync, and Pro settings exports nothing; nothing there is a picture control.
  The debloat list for this TV disables the hotel-mode service and its Node.js runtimes; Pro
  settings and IP control (which the REST path needs) stay.
- **`autoLocalDimming` over REST.** It has no menu item on this OLED, so we do not know what it
  maps to, and leave it where it was (`medium`).
- **Chasing firmware.** No release promises a brighter picture; see below.

## Firmware trail

The Indian XR-83A80L takes the Asia-Pacific packages. These are the releases Sony documents, with
the dates collected on 20 October 2026. The 6.8xxx releases share notice 00363716, which Sony
updates in place, so those links now open the newest one.

| Version | Released | What Sony lists | Notice |
|---|---|---|---|
| PKG6.7612 | 12 Sep 2024 | Sony account and smart speaker support, general performance. Earlier notes rolled in include XR Clear Image for the A80L, a clarity feature | [Sony India](https://www.sony.co.in/electronics/support/software/00292261) |
| PKG6.8100 | 20 Aug 2025 | Adds Prime Video Calibrated mode, a new Eco interface and a browser update; removes Wi-Fi Direct; general performance | [Sony India](https://www.sony.co.in/electronics/support/lcd-tvs-android-x90l_x91l-series/xr-75x90l/software/00363716) |
| PKG6.8160 | 21 Jan 2026 | Fixes Wi-Fi and settings access failing after an Android upgrade and long use; general performance | [Sony Asia](https://www.sony-asia.com/electronics/support/oled-tvs-android-a80l_a83l_a84l-series/xr-83a80l/software/00363716) |
| PKG6.8196 | 22 Apr 2026 | General performance | [Sony Asia](https://www.sony-asia.com/electronics/support/lcd-tvs-android-x95l-series/xr-85x95l/software/00363716) |
| PKG6.8224 | 15 Jul 2026 | General performance; the longer list on the page is earlier benefits carried over. The latest for India as of 20 Oct 2026 | [Sony India](https://www.sony.co.in/electronics/support/software/00363716), [A80L downloads](https://www.sony-asia.com/electronics/support/oled-tvs-android-a80l_a83l_a84l-series/xr-83a80l/downloads) |

None of them promises brighter HDR or Dolby Vision, or a change to tone mapping, so an update is
not a brightness fix. The 6.8224 notice says a set below 6.3770 should install 6.7612 first.

A PKG6.8156 notice (26 Nov 2025) also exists, but its
[Asia-Pacific version](https://www.sony-asia.com/electronics/support/oled-tvs-android-a95k-series/xr-65a95k/software/00363757)
lists the A80K and A95K generation, and the
[A80L listing](https://www.sony-asia.com/electronics/support/oled-tvs-android-a80l_a83l_a84l-series/xr-83a80l/software/00363735)
found for it is the Hong Kong package, so it is not counted here.

## Applied on the reference TV (XR-83A80L)

Applied on 22 to 24 October 2026 with `tools/sonyui.py`; REST was used to read only (no pairing). Each
row is one picture memory; values in brackets are what the tool recorded as "before". Firmware: build
`BRAVIA_VH22_M_PA-user 12 STT2.230505.001.S136 682241`, built 5 June 2026, security patch 1 May 2026
(Settings → System → About does not show the PKG number on this TV).

| Memory and signal | What changed | Already as wanted |
|---|---|---|
| Home, SDR | Picture mode Custom (Vivid); Ambient light sensor Off (On); Gamma 0 (−2); Peak luminance High (Medium); Reality Creation Auto (Manual); Motionflow Smoothness 1 (Min) | Brightness Max, Contrast 90, Colour 50, Hue 0, Expert 1, Black adjust, Adv. contrast enhancer and Live Colour Off, Clearness Low, Film mode High |
| Prime Video | Prime Video calibrated mode Off (On) | |
| Netflix | Netflix calibrated mode Off (On) | |
| Video apps, SDR (Netflix in front) | Picture mode Custom (Vivid); Peak luminance High (Off); Reality Creation Auto (Manual); Smoothness 1 (Min) | Ambient light sensor Off, Gamma 0, Brightness Max, Contrast 90, Expert 1 |
| Video apps, HDR10 (YouTube HDR clip, paused) | HDR tone mapping Brightness preferred (Gradation preferred) | Everything else: Custom keeps one set of values for SDR and HDR in a memory, so the SDR tuning already applied |
| Dolby Vision | Dolby Vision Bright, tuned: pending (needs a Dolby Vision title on screen) | |

The screensaver is Google TV's Ambient mode again (`tools/screensaver.sh google --after 5`), starting
after 5 idle minutes; its test showed photos, the photographer's credit, the weather and the time, so it
loads content here even with Google TV's launcher disabled.

Found along the way: the video-app memory's Custom had Peak luminance Off, the dimmest setting, and every
non-Vivid mode had the light sensor's automatic adjustments on. Pixel shift was already On. Netflix
gives Android no title or artwork for what is playing.
