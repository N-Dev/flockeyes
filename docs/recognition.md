# How well the app tells cows apart

Measured by `tools/eval_reid.py` (run by the *Models* workflow) on a computer, not on a phone and not on
your herd. Read it as what to expect at best, not a promise.

## In short

- **Followed without a break, a cow keeps its name** whatever it looks like: that is tracking, not
  recognition, and it is the dependable part.
- **Seen again a moment later** (it left the picture and came back, or the app lost it behind another
  cow): in fields, looks of one cow are much more alike than looks of different cows, and the app mostly
  gets this right. In the barn photos it is right 70% of the time and otherwise
  usually says nothing rather than something wrong.
- **Seen again another day**: unproven in fields (there are no photos to measure it on), and poor in the
  barn photos: 5% named right, 0% named wrong, the rest not named
  (so the cow is learnt a second time, to be merged by hand in the Herd tab).
- A cow's two sides look different, and cows with plain coats look the same. Neither can be fixed by a
  better threshold.

## The parts

**The model.** MegaDescriptor-T-224 (27.5 million parameters,
<https://huggingface.co/BVRA/MegaDescriptor-T-224>, CC BY-NC 4.0), exported to ONNX. The app ships the
**8-bit** version (30 MB): on the barn photos
the two give the same answers (most alike other photo is the same cow: full 33.4%, 8-bit
33.2%; their descriptions agree to 0.980). A photo takes 147 ms (full) or
109 ms (8-bit) on GitHub's machine with two threads.

**The tuning** (`models/cow-tuning.bin`, made by `tools/make_tuning.py`). The model's description of a
picture changes with how the cow stands, how its box was cut and what's behind it, as well as with which
cow it is. The tuning scales down the 64 directions that looks of ONE animal vary most
along, worked out from 1237 barn photos (grouped by cow) and 261 looks from field videos (grouped by
tracked cow). Everything below marked "tuned" used a tuning made without the cows (or the video) being
tested.

**The rule.** A look is named when it is at least 0.55 like a cow in the herd and that cow is 0.12
clear of the next most alike one. A cow less than 0.42 like every cow in the herd is new. In between,
the app waits for more looks (up to eight), then learns it as new. The "How sure" setting scales the
0.12.

## In fields

19 videos from Wikimedia Commons of cows in fields and yards (Holsteins, red-and-white
dairy cows, Montbéliardes, Belted Galloways; listed in `tools/clips.json`), played through the app's own
cow finder and tracker. 274 looks of 49 tracked cows.

| | Plain | Tuned |
|---|---|---|
| Two looks of one tracked cow, 2 s or more apart: how alike (average; lowest 5%) | 0.86; 0.62 | 0.83; 0.54 |
| Looks of two cows in view together: how alike (average; highest 5%) | 0.39; 0.68 | 0.32; 0.52 |
| One cow's pair is the more alike of the two (chance: 50%) | 98.3% | 99.2% |
| Pairs of one cow at least 0.55 alike | | 94% |
| Pairs of different cows at least 0.55 alike | | 2.7% |

(1507 pairs of one cow, 111 pairs of different cows.) These are looks seconds apart in
the same light: it says the app can tell a cow it has just seen from the others around it. It says nothing
about knowing a cow again next week.

## In the barn

1237 side-on colour photos of 136 Holstein cows, about 9 each, taken on different
days ("Holstein Cattle Recognition", Bhole, Falzon, Biehl, Azzopardi; Dairy Campus Leeuwarden; CC0;
<https://doi.org/10.34894/O1ZBSA>). A hard test, and not what the app is for: each cow stands in a stall
behind metal rails with its top half hidden by a banner, so a picture of it is mostly rails, legs and
belly, and the camera was moved between days. The cow finder boxed the cow in 1192 of them
(96.4%); the others were used whole.

| | Plain | Tuned |
|---|---|---|
| The most alike other photo (of 1236) is the same cow | 33.2% | 47.0% |
| **A moment later** (the same photo, its box moved a little, lighter or darker, smaller; 413 looks): named right | 9% | 70% |
| ... named wrong | 0.0% | 0.0% |
| ... a cow that isn't in the herd given another cow's name | 0.0% | 0.7% |
| **Another day** (the herd knows each cow from its first five photos; 557 later photos, one look each): named right | 0% | 5% |
| ... named wrong | 0.0% | 0.4% |
| ... not named (it would be learnt again as a new cow) | 100% | 95% |
| ... a cow that isn't in the herd given another cow's name | 0.2% | 1.3% |

In the app a cow gets up to eight looks before it's named and its entry holds up to twelve, gathered as
it moves; here each look stood alone, which is harder.

## What was tried

- Bigger and other models, on the barn photos (most alike other photo is the same cow, plain):
  MegaDescriptor-T 33%, -S 42%, -B 47%, -L-384 50% (eighteen times slower); DINOv2 ViT-S 41%, ViT-B 37%;
  plain ImageNet features 35%. None is good at it, and after tuning the small ones are level.
- Scaling down everything the looks vary along (not only how one animal's looks vary): better in the barn,
  worse than no tuning in fields, where most of what varies is which cow it is.
- Matching small patches between two pictures (XFeat): in the barn it matches the rails.
