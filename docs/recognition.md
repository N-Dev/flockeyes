# How well the recognition model tells cows apart

Measured by `tools/eval_reid.py` (run by the *Models* workflow), not on a phone.

**The photos.** 1237 side-on colour photos of 136 Holstein cows, about 9 each, from
"Holstein Cattle Recognition" (Bhole, Falzon, Biehl, Azzopardi; Dairy Campus Leeuwarden; CC0;
<https://doi.org/10.34894/O1ZBSA>). They are 640 x 480, taken indoors with the cow behind metal rails. The app's cow finder boxed the cow in 1192 of them (96.4%); the others
were used whole.

**The model.** MegaDescriptor-T-224 (27.5 million parameters), exported to ONNX. The app ships the
**8-bit** version (30 MB).

| | Full (32-bit, 113 MB) | 8-bit (30 MB) |
|---|---|---|
| Most alike other photo is the same cow (top-1) | 33.4% | 33.5% |
| "Same cow" threshold (2% of other cows pass) | 0.95 | 0.95 |
| Photos whose own cow passes it | 2.4% | 1.9% |
| Photos named right at that threshold | 2.1% | 1.2% |
| Time a photo (2 threads, GitHub's machine) | 67 ms | 47 ms |

The two versions agree closely on these photos (mean cosine 0.980).

**What this does and doesn't show.** Each cow's photos here were taken in the same place, side-on, at the
same distance and from the same side, partly hidden by rails. That is close to the gate count's
situation. Some of a cow's photos may have been taken moments apart, which is easier than knowing a cow
again days later. A field is harder: cows are further away, at every angle, and hide each other. And a
cow's two sides have different markings, so a cow learnt from its left looks like a stranger from its
right until the app has seen both.

## How alike photos are (cosine, int8)

Best match among the same cow's other photos:

     0.10  1
     0.30  3
     0.35 # 7
     0.40 # 12
     0.45 # 13
     0.50 ## 20
     0.55 ## 25
     0.60 ## 20
     0.65 #### 39
     0.70 ####### 71
     0.75 ############ 122
     0.80 ############################ 281
     0.85 ######################################## 407
     0.90 ################### 193
     0.95 ## 23

Best match among every other cow's photos:

     0.55  3
     0.60  3
     0.65  4
     0.70 # 20
     0.75 ##### 85
     0.80 ############## 247
     0.85 ######################################## 692
     0.90 ######### 152
     0.95 ## 31
