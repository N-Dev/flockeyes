# Flock Eyes

Counts dairy cows and recognises each one again, on the phone. Nothing is uploaded.

**Install on an Android phone:** open
<https://github.com/N-Dev/flockeyes/releases/latest/download/FlockEyes.apk> on the phone, allow your browser to
install unknown apps when Android asks (once), and tap Install.

- **Field count:** tap Start and hold the phone on the cows. You get how many are in view, and each cow
  standing clear of the others is learnt, or named if the app knows it, so that it isn't counted twice
  when it wanders out of the picture and back.
- **Gate count:** fix the phone at a gap, a gateway or the way out of the parlour, draw a line across it,
  and cows are counted each way as they cross and named as they pass. This is the one to use for a herd
  on the move, and where recognition has the best chance: one cow at a time, close, side-on.
- **Herd:** every cow it knows, with the small pictures it's recognised from. Give each its name or tag
  number, and merge two entries that are one cow.
- **Counts:** saved counts, with who was counted, as CSV if you want it in a spreadsheet.
- Also: counting the cows in a video, backup and restore, a speed test that finds the fastest way to run
  the AI on your phone, and a debug mode that shows what the AI sees and every match score.

## What to expect

Counting is the dependable part; recognising is harder, and how well it does on your herd is for you to
find out (debug mode shows its working).

- **It counts what it can see at once, plus cows it has told apart.** A herd that fills more than one
  screenful and stands close together is undercounted in a field count: bring them past a gate count.
- **It tells cows apart by the markings on their sides**, so it needs them side-on, big in the picture and
  standing clear. Cows bunched together are counted but not named.
- **A cow followed without a break keeps its name**, and the app learns what it looks like as it moves.
  That is how it comes to know both sides of a cow, which otherwise look like two different animals: a cow
  learnt from the left is a stranger from the right until the app has watched it turn round, or you merge
  the two entries in the Herd tab.
- **Plain-coated cows can't be told apart.** They're still found and counted.
- **Knowing a cow again on another day is unproven.** In the only photos there are to measure it on (cows
  half hidden behind rails in a barn) it mostly fails safe: it learns the cow again rather than calling it
  by another's name. See [docs/recognition.md](docs/recognition.md). The "How sure" setting trades cows
  learnt twice against cows mixed up.

## What's here

| Folder | |
|---|---|
| `android/` | The Android app (Kotlin, CameraX, ONNX Runtime, Jetpack Compose). See [android/README.md](android/README.md). |
| `models/` | The cow finder (YOLOX, Apache 2.0), the recognition model (MegaDescriptor, **CC BY-NC 4.0: non-commercial only**) and its tuning, with their licences. |
| `tools/` | Fetch the recognition model, export it for the phone, make its tuning and measure it; run by the *Models* workflow. |
| `tests/assets/` | Test pictures: frames of real videos of cows, and barn photos. Where each came from is in the README there. |
| `docs/` | The measurements. |
| `web/` | The first, simpler version, for a browser. |

Because of the recognition model's licence, the app is for your own use and can't be sold.
