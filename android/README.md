# Flock Eyes for Android

Counts dairy cows and recognises each one again. Everything runs on the phone with ONNX Runtime for
Android, and nothing is uploaded.

**Install on a phone:** open <https://github.com/N-Dev/flockeyes/releases/latest/download/FlockEyes.apk> on
the phone, allow your browser to install unknown apps when Android asks (once), and tap Install. New
versions install over the old one and keep the herd, counts and settings. Android 8 or later, 64-bit (arm64).

| Tab | What it does |
|---|---|
| **Field** | A field count: tap Start and hold the phone on the cows. Each cow standing clear of the others is named if the app knows it, or learnt if it doesn't. Shows cows counted (never fewer than were in view at once), in view now, new cows, and which cows of the herd haven't been seen yet. Pinch or use the zoom buttons; "Far away" analyses the picture in two halves so distant cows show bigger. Can also count the cows in a video (the menu, or *Share → Flock Eyes* from the gallery). |
| **Gate** | A gate count: set a line across a gap, gateway or the way out of the parlour, then Start. Cows are counted each way as they cross it and named as they pass. Carries on with the screen off; the screen dims while counting and the tabs are hidden so it can't be stopped by accident. |
| **Herd** | Every cow the app knows, with the small pictures it's recognised from. Give each a name or tag number, remove a wrong picture, merge two entries that are one cow (the app points out pairs that look alike), delete, export as CSV. |
| **Counts** | Saved counts: who was counted and when, which cows of the herd weren't seen, CSV export. |
| **Settings** | How sure it must be before saying it's a cow it knows, whether to learn new cows, the cow finder, the AI engine and its speed test, backup and restore, debug mode, updates. |

## How it works

1. **The cow finder** (YOLOX, the model TrafficSight uses for road users, which also knows cows) boxes every
   cow in each frame.
2. **The tracker** follows each box from frame to frame.
3. **The recogniser** (MegaDescriptor) turns a picture of a cow into 768 numbers; pictures of the same cow
   give similar numbers. A few pictures of each tracked cow ("looks") go through it, on a second thread so
   the picture keeps moving. Only good pictures are used: the cow finder is sure of the box, it's big
   enough, not cut off by the edge, and not touching another cow's (a small box against a big one is taken
   for a piece of that animal, a head or a rump, and left out altogether).
4. **The tuning.** The numbers change with how a cow stands and how its box was cut as well as with which
   cow it is. Before two looks are compared, what looks of ONE cow vary along is scaled down
   (`core/reid/Tuning.kt`, `models/cow-tuning.bin`).
5. **Naming.** A cow's looks are compared with the looks kept for every cow in the herd. Alike enough to one
   and clearly more than to any other: it's that cow. Like none: it's learnt as a new cow. In between: the
   app waits for more looks. One cow can't be two boxes at once, and a cow that looks like one standing
   elsewhere in view isn't learnt (an entry that can't be told from another is no use).
6. **Following counts for more than looks.** A cow followed without a break keeps its name however it
   looks, and its new looks are added to what's known of it: that is how the app learns a cow's other
   side. Only a box that has been in among other animals can have slipped onto another cow; it is renamed
   if its looks come to be clearly another cow's.
7. **Counting.** A field count is the number of different cows named, and never fewer than were in view at
   once (the middle of five frames, so a box that flickers doesn't count). A gate count is the number of
   times a cow's feet went from clearly one side of the line to clearly the other, each way.

What it can't do: count a herd that is bunched up and wider than the picture (use a gate count); tell
apart cows with plain coats; know that the left and right side of a cow are the same animal (unless it
watches the cow turn round, or you merge the two entries); recognise cows that are small in the picture or
hidden behind each other. What was measured, in field videos and on barn photos, is in
[docs/recognition.md](../docs/recognition.md).

## Debug mode

Settings → Developer → Debug mode. On the Field and Gate screens it draws what the cow finder found in the
frame (dashed, with what it called it and how sure), each cow's track number, how often it's been seen and
looked at, why it wasn't looked at this time ("small", "edge", "unsure", "part", "overlap", "close", "cut"),
and the two cows in the herd it's most like with their scores. A strip shows the camera and analysis frame
rates, how long each step takes, the bars a look must clear, which accelerator each model is on, and the
phone's heat, battery and memory, with a graph of frame times. Scores also show in the Herd and Counts
tabs. In Settings a slider sets exactly how far a cow must stand out from the next most alike to be named,
and the herd's pictures can be exported as a zip. The **debug log**
lists what the app did, with **Copy** and **Share** (with the phone's details) for bug reports.

## The code

- `core/`: plain Kotlin, no Android. `detect` (the cow finder), `track`, `reid` (the recogniser and the
  tuning), `herd` (the cows and their looks, merging, finding doubles), `count` (`Scan` does the naming
  and counting for both kinds of count; `Gate` is the line's geometry), `report` (CSV).
- `app/`: the Android app. `scan` (the live counters and their screens), `herd`, `history`, `settings`,
  `video`, `camera` (the camera belongs to the app, so a gate count carries on without a screen), `ai` (the
  models and the speed test), `data` (SQLite, backup, updates), `debug`, `service`.
- The models are in `../models` and copied into the app when it's built.

### Tests

- `core/src/test`: the naming and counting logic with pretend cows (learning, knowing again, one cow not in
  two places, a cow followed as it turns round, a box that slips onto another cow, gate crossings and a
  cow dithering on the line, merging); the tuning's sums; and the real models on real pictures
  (`../tests/assets`): the cow finder and the recogniser against what the Python tools made of the barn
  photos, and the whole chain on the frames of a real video of cows (`Replay.kt` plays any folder of
  frames through it and can draw what it saw) and on a cow walking through a pretend gate.
- `app/src/androidTest`: on an emulator in CI: the models through the app's own engine (and which
  accelerators work), every tab, a field count from the frames of the real video and a gate count from
  pretend frames through to the saved count and the herd, a gate count in the background, debug mode,
  backup and restore, the speed test. Screenshots are saved.

CI builds the app, runs both, and publishes the arm64 APK as a GitHub release (from `main`; `next` only
builds and tests). The build log, test results and emulator screenshots of the last run are on the
[`android-ci`](https://github.com/N-Dev/flockeyes/tree/android-ci) branch.

On a machine without ONNX Runtime for Java, `python3 tools/bridge.py` runs the models for the core tests
(`-Dflockeyes.bridge=http://127.0.0.1:8790`).

### Signing key

Android only installs an update over an app signed with the same key. By default every build is signed
with `keystore/flockeyes.jks` (password `flockeyes-sideload`), which is in the repository so CI can use it;
that means anyone could sign an app that installs over Flock Eyes on a phone that has it. The workflow
switches to a private key as soon as one is in the repository's secrets:

1. Make a keystore: `keytool -genkeypair -keystore flockeyes-private.jks -alias flockeyes -keyalg RSA -keysize 4096 -validity 10000`.
2. In GitHub: Settings → Secrets and variables → Actions → New repository secret:
   `FLOCKEYES_KEYSTORE_BASE64` (the output of `base64 -w0 flockeyes-private.jks`) and
   `FLOCKEYES_KEYSTORE_PASSWORD`; also `FLOCKEYES_KEY_ALIAS` and `FLOCKEYES_KEY_PASSWORD` if they differ
   from `flockeyes` and the keystore password.
3. Keep the keystore file somewhere safe: without it, no update can ever install over that build again.

Phones with the old key's build then have to reinstall once: in the app, Settings → Backup → **Back up**,
uninstall Flock Eyes, install the new APK, then Settings → Backup → **Restore**.
