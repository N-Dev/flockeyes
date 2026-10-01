# How well the recognition model tells cows apart

Measured by `tools/eval_reid.py` (run by the *Models* workflow), not on a phone.

**The photos.** 2474 side-on colour photos of 136 Holstein cows, about 18 each, from
"Holstein Cattle Recognition" (Bhole, Falzon, Biehl, Azzopardi; Dairy Campus Leeuwarden; CC0;
<https://doi.org/10.34894/O1ZBSA>). They are small (320 x 240). The app's cow finder boxed the cow in
783 of them (31.6%); the others were used whole.

**The model.** MegaDescriptor-T-224 (27.5 million parameters), exported to ONNX. The app ships the
**8-bit** version (30 MB).

| | Full (32-bit, 113 MB) | 8-bit (30 MB) |
|---|---|---|
| Most alike other photo is the same cow (top-1) | 15.3% | 15.0% |
| "Same cow" threshold (2% of other cows pass) | 0.95 | 0.95 |
| Photos whose own cow passes it | 5.7% | 4.8% |
| Photos named right at that threshold | 3.0% | 2.5% |
| Time a photo (2 threads, GitHub's machine) | 66 ms | 53 ms |

The two versions agree closely on these photos (mean cosine 0.980).

**What this does and doesn't show.** Each cow's photos here were taken in the same place, side-on, at the
same distance, so this is close to the best case: the gate count's situation. A field is harder: cows are
further away, at every angle, and partly hidden. And a cow's two sides have different markings, so a cow
learnt from its left looks like a stranger from its right until the app has seen both.

## How alike photos are (cosine, int8)

Best match among the same cow's other photos:

     0.10  1
     0.20  1
     0.25  4
     0.35  3
     0.40  5
     0.45 # 13
     0.50 ## 29
     0.55 # 24
     0.60 ## 45
     0.65 ##### 95
     0.70 ######### 159
     0.75 ############ 221
     0.80 ############################## 536
     0.85 ######################################## 724
     0.90 ########################### 496
     0.95 ####### 118

Best match among every other cow's photos:

     0.30  1
     0.40  1
     0.50  1
     0.55  1
     0.60  4
     0.65  7
     0.70 # 18
     0.75 #### 93
     0.80 ############### 371
     0.85 ########################### 671
     0.90 ######################################## 999
     0.95 ############ 307
