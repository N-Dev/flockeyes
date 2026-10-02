# How well the app counts a field

Measured by playing the frames of real videos through the app's own code on a computer
(`android/core/src/test/.../ClipsTest.kt`), not on a phone and not on your herd. The videos are freely
licensed ones from Wikimedia Commons (`tools/clips.json`, `tools/pans.json`; the *Research* workflow
fetches them), five frames a second. "By eye" is how many cattle can be made out in the frames by a person
looking at them: where that was hard, a range is given. Read it as what to expect, not a promise.

## In short

- **Cows standing apart, phone held still or panned from one spot: the count is right, or one out.**
- **Cows too small in the picture aren't found**: far across a field, a cow about 3% of the picture wide
  is missed. Zoom in, or turn on "Far away" (the picture is then looked at in two halves, so everything
  is twice the size to the cow finder), which found them.
- **Cows bunched together are undercounted**, by a quarter to a third in the tight bunches below: a cow
  hidden behind another can't be counted by anything that looks.
- **Walk about while counting and it overcounts**: 9 for 6 in the one clip filmed on the move. A cow's
  place only means something from one spot, so stand still and turn. Cattle that are themselves on the
  move weren't measured (I couldn't count them by eye either): for those, use the gate count.

## Camera still (or nearly)

| Clip | What's in it | By eye | App |
|---|---|---|---|
| `rockhill21` | two lying down, far off | 2 | 2 |
| `rockhill23` | one standing, one lying | 2 | 2 |
| `seebach3` | two lying, close | 2 | 2 |
| `rade_g03` | two grazing, close; the camera follows them half a picture's width | 2 | 2 |
| `rockhill24` | two standing, one lying | 3 | 3 |
| `koeien` | four Holsteins at a heap of silage, big in the picture | 4 | 4 |
| `corsino07` | four at a pond, far off | 4 | 4 |
| `wilthen` | one near, the rest far across the field | 5 or 6 | 6 |
| `rade_g07` | five or six grazing, close, half hiding each other | 5 or 6 | 5 |
| `rockhill22` | seven about a yard | 7 | 7 |
| `rockhill25` | seven or eight round a hay feeder | 7 or 8 | 7 |
| `seebach2` | seven or eight, lying and standing on a slope | 7 or 8 | 8 |
| `rockhill20` | six round a feeder, hiding each other | 6 | 4 |
| `rade_g01`, `rade_g02` | about seven heifers in a tight bunch, close | about 7 | 5 |
| `rade_c02` | eleven or twelve heifers shoulder to shoulder | 11 or 12 | 8 |
| `appleton24` | thirteen to fifteen lying in long grass, far off, many of them white | 13 to 15 | 10 |
| `appleton23` | seventeen or eighteen, some in a heap at one side | 17 or 18 | 16 |
| `wendell05` | thirty or more heifers in a tight line, facing the camera | 30 or more | 21 |

## Panned

**A window slid across the frames**, half as wide as the video, as a phone's view would pass over a wider
scene: across, back, and across again. The cows move as cows do; only the turning is pretend. The count
after each pass:

| Clip | By eye | Across | Back | Across again |
|---|---|---|---|---|
| `rockhill22` (the yard; the repository's test, `tests/assets/clips/yard`) | 7 | 7 | 7 | 7 |
| `rockhill24` | 3 | 2 | 3 | 3 |
| `appleton24` | 13 to 15 | 11 | 15 | 15 |
| `appleton23` | 17 or 18 | 11 | 15 | 17 |
| `rade_c02` | 11 or 12 | 8 | 8 | 9 |
| `koeien` | 4 | 3 | 4 | 5 |

Going back over cows already counted doesn't count them again where they stand apart (the yard). The
counts that rise on later passes are mostly cows found late (boxes come and go among cows in a heap);
`koeien` ends one more than there are, which I haven't traced.

How far the "phone" had turned, worked out from the pictures alone, was within 1% of the truth where the
video's own camera was fixed and the background in view (the yard, `rockhill24`, `rade_c02`,
`appleton23`); 2 to 6% out in `appleton24` and `koeien`, which were themselves filmed by hand; and up to
18% out in close-ups where moving cows fill the picture (`rade_g01`, `rade_g07`).

**Really panned, by hand, from one spot:**

| Clip | What's in it | By eye | App |
|---|---|---|---|
| `andorra` | cattle on a mountain pasture, the camera turned through nearly two picture widths | 10 or 11, and several more in the distance only a few pixels high | 9, none twice |
| `seebach1` | cows lying on a slope: turned through about half a picture's width, then zoomed in | 7 or 8 | 8 |

## Far off

| Clip | What's in it | By eye | App | App with "Far away" |
|---|---|---|---|---|
| `corsino06` | the four of `corsino07` from further back, each about 3% of the picture wide, and one more beyond | 4 or 5 | 0 | 4 |
| `andorra` | as above | 10 or 11, and more in the distance | 9 | 11 |
| `wendell05` | as above | 30 or more | 21 | 23 |

## Carried about

| Clip | What happens in it | By eye | App |
|---|---|---|---|
| `aberfeldy` | filmed while walking along a road towards the cattle, which end up seen through a fence | 6 | 9 |
| `bexhill` | from a drone flying over a herd, part of which runs from it | about 30 | 30 |

The drone's count is no better than luck: cattle running, seen from something flying, are neither what
the app is for nor what one clip can show.

## Trying it on other footage

Unpack a folder of frames per clip (`NAME/0001.jpg` ...) and, in `android/`:

    ./gradlew :core:test --tests '*ClipsTest*' -Dflockeyes.clips=DIR -Dflockeyes.out=OUT

`OUT` gets frames with what the app saw drawn on them: each cow's box and name, and (dotted) the places
cows have been counted at. The same drawing is in the app's debug mode.
