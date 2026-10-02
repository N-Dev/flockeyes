#!/usr/bin/env python3
"""Measures the cow recognition model, makes the tuning that goes with it, and writes the app's settings for it.

The photos: "Holstein Cattle Recognition" (Bhole et al., Dairy Campus Leeuwarden; 136 cows, about nine
RGB photos each, taken on different days; CC0; https://doi.org/10.34894/O1ZBSA), downloaded from
DataverseNL. They are a hard test: each cow stands in a stall behind metal rails, with its top half
hidden by a banner. Looks taken by the app in videos of cows in fields are in tools/data (see make_tuning.py).

For every photo the app's own steps are followed: the cow finder (YOLOX) boxes the cow, the box is squashed
to 224 x 224 and the recognition model describes it. Then, with and without the tuning:

  * top-1: is the most alike other photo one of the same cow?
  * "a moment later": the same photo with its box moved a little, a bit lighter or darker and smaller, as
    the next look of a tracked cow would be: is it named (and right) by the app's rule, and how often is
    a cow that isn't in the herd given some other cow's name?
  * "another day": each cow's first five photos are the herd; every later photo is one look to be named.
  * in the field: looks of one tracked cow against looks of two cows in view together.

Writes models/cow-reid.onnx (the version that ships), models/cow-tuning.bin, models/cow-reid.json (how to
feed the model, and the bars a look must clear), docs/recognition.md (the results) and a few of the photos
as test pictures (tests/assets/cows).

Run by .github/workflows/models.yml after prepare_reid.py.
Usage: eval_reid.py BUILD_DIR [DATA_DIR]
"""
import collections
import io
import json
import os
import re
import shutil
import sys
import time
import urllib.request
import zipfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import cowdetect  # noqa: E402
import make_tuning  # noqa: E402

BUILD = sys.argv[1] if len(sys.argv) > 1 else "build/reid"
DATA = sys.argv[2] if len(sys.argv) > 2 else "build/data"
DOI = "doi:10.34894/O1ZBSA"
API = "https://dataverse.nl/api"
UA = {"User-Agent": "FlockEyes model evaluation (https://github.com/N-Dev/flockeyes)"}
SIZE = 224
IMG_EXT = (".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff")
RGB_WORDS = {"rgb", "visible", "visual", "color", "colour", "vis"}
THERMAL_WORDS = {"thermal", "ir", "flir", "infrared", "therm", "th"}


def log(*a):
    print(*a, flush=True)


# ---------------------------------------------------------------- the photos

def get(url, tries=4):
    last = None
    for i in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60)
        except Exception as e:  # noqa: BLE001
            last = e
            log("  retrying", url, repr(e))
            time.sleep(5 * (i + 1))
    raise last


def fetch(url, path):
    """Downloads a file with curl (it retries, follows the archive's redirects, and can be told to use IPv4)."""
    import subprocess

    base = ["curl", "-fsSL", "--retry", "4", "--retry-all-errors", "--connect-timeout", "30", "--max-time", "900",
            "-A", UA["User-Agent"], "-o", path, url]
    for extra in (["-4"], []):
        r = subprocess.run(base[:1] + extra + base[1:], capture_output=True, text=True)
        if r.returncode == 0 and os.path.getsize(path) > 0:
            return
        log("  curl", " ".join(extra), "failed:", r.stderr.strip()[-300:])
    raise RuntimeError(f"couldn't download {url}")


def download(dest):
    os.makedirs(dest, exist_ok=True)
    if os.path.exists(os.path.join(dest, ".done")):
        log("the photos are already here")
        return
    meta = json.load(get(f"{API}/datasets/:persistentId/?persistentId={DOI}"))
    files = meta["data"]["latestVersion"]["files"]
    log("dataset files:", [(f["dataFile"].get("filename"), f["dataFile"].get("filesize")) for f in files])
    for f in files:
        d = f["dataFile"]
        name = d.get("filename") or f.get("label") or str(d["id"])
        path = os.path.join(dest, name)
        t0 = time.time()
        fetch(f"{API}/access/datafile/{d['id']}", path)
        log("  downloaded", name, os.path.getsize(path), "bytes in %.0f s" % (time.time() - t0))
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as z:
                z.extractall(os.path.join(dest, os.path.splitext(name)[0]))
            os.remove(path)
    open(os.path.join(dest, ".done"), "w").close()


def words(path):
    return set(re.split(r"[^a-z0-9]+", path.lower()))


def kind_of(path):
    name = os.path.basename(path).lower()
    # This dataset: "FLIR1945- full photo.jpg" is the camera's colour photo (640 x 480), "FLIR1945- photo.jpg"
    # the part of it the thermal picture covers, and "FLIR1945.jpg" the thermal picture itself.
    if "full photo" in name:
        return "rgb"
    if "- photo" in name:
        return "rgb-part"
    if re.fullmatch(r"flir\d+\.jpe?g", name):
        return "thermal"
    w = words(path)
    if w & RGB_WORDS:
        return "rgb"
    if w & THERMAL_WORDS:
        return "thermal"
    return None


def identity(rel):
    """Which cow a photo is of: the folder it's in (above any rgb/thermal folder)."""
    parts = rel.replace("\\", "/").split("/")[:-1]
    while parts and (words(parts[-1]) & (RGB_WORDS | THERMAL_WORDS) or parts[-1].lower() in ("images", "image", "data")):
        parts = parts[:-1]
    return "/".join(parts[-2:]) if parts else "?"


def scan(dest):
    """Every picture in the download: (relative path, width, height, kind)."""
    from PIL import Image

    out = []
    for root, _, names in os.walk(dest):
        for n in sorted(names):
            if not n.lower().endswith(IMG_EXT):
                continue
            p = os.path.join(root, n)
            try:
                with Image.open(p) as im:
                    w, h = im.size
            except Exception:  # noqa: BLE001
                continue
            rel = os.path.relpath(p, dest)
            out.append((rel, w, h, kind_of(rel)))
    # In the same order on every machine (folders come back in no particular order).
    out.sort()
    return out


# ---------------------------------------------------------------- the model

def embedder(path, mean, std):
    sess = cowdetect.session(path)
    m = np.array(mean, dtype=np.float32).reshape(1, 3, 1, 1)
    s = np.array(std, dtype=np.float32).reshape(1, 3, 1, 1)

    def run(bgr_crop):
        import cv2

        rgb = cv2.cvtColor(cv2.resize(bgr_crop, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR), cv2.COLOR_BGR2RGB)
        x = (rgb.astype(np.float32) / 255.0).transpose(2, 0, 1)[None]
        e = sess.run(None, {sess.get_inputs()[0].name: (x - m) / s})[0][0]
        return e / (np.linalg.norm(e) + 1e-12)

    return run


def measure(E, y):
    """Top-1 and the genuine / impostor best-match similarities for unit vectors E with cow numbers y."""
    S = E @ E.T
    np.fill_diagonal(S, -2)
    same = y[:, None] == y[None, :]
    np.fill_diagonal(same, False)
    has_mate = same.any(1)
    nn = S.argmax(1)
    top1 = float((y[nn] == y)[has_mate].mean())
    genuine = np.where(same, S, -2).max(1)[has_mate]
    other = y[:, None] != y[None, :]
    impostor = np.where(other, S, -2).max(1)
    return top1, genuine, impostor, has_mate


# The bars a look must clear in the app (as the tuning sees the looks): at least MATCH like a cow of the
# herd and MARGIN clear of the next most alike; a cow less than FRESH like every cow is new. Chosen from
# the measurements below: in the field clips two looks of one tracked cow are 0.8 to 0.9 alike and looks
# of different cows seldom over 0.55; in the barn a look a moment later is about 0.7 like its own cow and
# 0.6 like the next, which is what MARGIN is for.
MATCH = 0.55
FRESH = 0.42
MARGIN = 0.12


def by_cow(S, gy, cows):
    """Look x cow: how like each cow a look is (its best match among the cow's photos)."""
    return np.stack([np.where(gy[None, :] == c, S, -9).max(1) for c in cows], 1)


def naming(C, own):
    """The app's rule on a look x cow table. `own`: the column of each look's own cow.
    Returns the shares named right, named wrong and not named; and, with the look's own cow taken out of
    the herd (so it is a new cow), the share wrongly given another cow's name."""
    rows = np.arange(len(C))
    o = np.argsort(-C, 1)
    top = o[:, 0]
    s1 = C[rows, top]
    s2 = C[rows, o[:, 1]]
    named = (s1 >= MATCH) & (s1 - s2 >= MARGIN)
    right = named & (top == own)
    C2 = C.copy()
    C2[rows, own] = -9
    o2 = np.argsort(-C2, 1)
    t1 = C2[rows, o2[:, 0]]
    t2 = C2[rows, o2[:, 1]]
    false = (t1 >= MATCH) & (t1 - t2 >= MARGIN)
    return {"right": float(right.mean()), "wrong": float((named & (top != own)).mean()), "unnamed": float((~named).mean()),
            "stranger_named": float(false.mean()), "own_mean": float(C[rows, own].mean()), "other_best_mean": float(t1.mean())}


def moment_later(img, box, rng):
    """The picture of a cow as its next look might be: the box moved a little, lighter or darker, smaller."""
    import cv2

    h, w = img.shape[:2]
    bw = box[2] - box[0]
    bh = box[3] - box[1]
    j = rng.uniform(-0.06, 0.06, 4)
    x1 = int(np.clip((box[0] + j[0] * bw) * w, 0, w - 8))
    y1 = int(np.clip((box[1] + j[1] * bh) * h, 0, h - 8))
    x2 = int(np.clip((box[2] + j[2] * bw) * w, x1 + 8, w))
    y2 = int(np.clip((box[3] + j[3] * bh) * h, y1 + 8, h))
    c = np.clip(img[y1:y2, x1:x2].astype(np.float32) * rng.uniform(0.88, 1.12) + rng.uniform(-8, 8), 0, 255).astype(np.uint8)
    k = rng.uniform(0.7, 1.0)
    return cv2.resize(c, (max(8, int(c.shape[1] * k)), max(8, int(c.shape[0] * k))), interpolation=cv2.INTER_AREA)


def field_pairs(F, clip, frame, group):
    """Looks from the field clips: pairs of one tracked cow two seconds or more apart, and pairs of two cows in view together."""
    S = F @ F.T
    same, other = [], []
    for i in range(len(F)):
        for j in range(i + 1, len(F)):
            if clip[i] != clip[j]:
                continue
            if group[i] == group[j]:
                if abs(frame[i] - frame[j]) >= 10:
                    same.append(S[i, j])
            elif abs(frame[i] - frame[j]) <= 2:
                other.append(S[i, j])
    return np.array(same), np.array(other)


def main():
    import cv2

    os.makedirs(BUILD, exist_ok=True)
    export = json.load(open(os.path.join(BUILD, "export.json")))
    download(DATA)
    files = scan(DATA)
    sizes = collections.Counter((w, h) for _, w, h, _ in files)
    kinds = collections.Counter(k for _, _, _, k in files)
    log("pictures:", len(files), "sizes:", sizes.most_common(6), "kinds by name:", dict(kinds))
    with open(os.path.join(BUILD, "dataset-files.txt"), "w") as f:
        for rel, w, h, k in files:
            f.write(f"{rel}\t{w}x{h}\t{k}\n")
    for rel, w, h, k in files[:40]:
        log("  ", rel, f"{w}x{h}", k)

    # The RGB photos: by name if the names say, else by size (320 x 240; the thermal ones are 640 x 320).
    if kinds.get("rgb"):
        rgb = [f for f in files if f[3] == "rgb"]
        how = "named rgb"
    elif kinds.get("thermal"):
        rgb = [f for f in files if f[3] is None]
        how = "not named thermal"
    else:
        small = min(sizes, key=lambda s: s[0] * s[1])
        rgb = [f for f in files if (f[1], f[2]) == small] if len(sizes) > 1 else files
        how = f"size {small}" if len(sizes) > 1 else "all"
    cows = collections.OrderedDict()
    for rel, w, h, k in rgb:
        cows.setdefault(identity(rel), []).append(rel)
    if len(cows) < 20:
        # Not a folder per cow: go by the number each file name starts with.
        log("folders give only", len(cows), "cows; going by file names instead")
        cows = collections.OrderedDict()
        for rel, w, h, k in rgb:
            m = re.match(r"(\D*\d+)", os.path.basename(rel))
            cows.setdefault(os.path.dirname(rel) + "/" + (m.group(1) if m else "?"), []).append(rel)
    counts = [len(v) for v in cows.values()]
    log(f"RGB photos ({how}): {len(rgb)} of {len(cows)} cows; per cow min {min(counts)} mean {np.mean(counts):.1f} max {max(counts)}")
    log("first cows:", list(cows)[:8])

    det = cowdetect.session(os.path.join(REPO, "models", "cows-tiny.onnx"))
    crops, labels, rels, boxes = [], [], [], []
    found = 0
    for ci, (cow, items) in enumerate(cows.items()):
        for rel in items:
            img = cv2.imread(os.path.join(DATA, rel))
            if img is None:
                continue
            dets = cowdetect.detect(det, img, conf=0.25)
            h, w = img.shape[:2]
            box = None
            if dets:
                b, s = max(dets, key=lambda d: (d[0][2] - d[0][0]) * (d[0][3] - d[0][1]))
                x1, y1, x2, y2 = int(b[0] * w), int(b[1] * h), int(np.ceil(b[2] * w)), int(np.ceil(b[3] * h))
                if x2 - x1 >= 24 and y2 - y1 >= 24:
                    box = b
                    crop = img[y1:y2, x1:x2]
                    found += 1
            if box is None:
                crop = img
            crops.append(crop)
            labels.append(ci)
            rels.append(rel)
            boxes.append(box)
    y = np.array(labels)
    log(f"cow finder: a cow boxed in {found} of {len(crops)} photos")

    # The model's page says to scale colours with mean and spread 0.5; its settings file says ImageNet's
    # values. Both are tried on the full model, and the better one is used.
    norms = {"half": ([0.5, 0.5, 0.5], [0.5, 0.5, 0.5]), "imagenet": ([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])}
    norm_top1 = {}
    for nm, (mean, std) in norms.items():
        run = embedder(os.path.join(BUILD, "cow-reid-fp32.onnx"), mean, std)
        En = np.stack([run(c) for c in crops]).astype(np.float32)
        norm_top1[nm] = measure(En, y)[0]
        log(f"colour scaling '{nm}': top-1 {norm_top1[nm]:.3f}")
    best_norm = max(norm_top1, key=norm_top1.get)
    export["mean"], export["std"] = norms[best_norm]
    log("using colour scaling:", best_norm)

    results = {}
    embs = {}
    for name in ("fp32", "int8"):
        path = os.path.join(BUILD, f"cow-reid-{name}.onnx")
        run = embedder(path, export["mean"], export["std"])
        t0 = time.time()
        E = np.stack([run(c) for c in crops]).astype(np.float32)
        ms = (time.time() - t0) / len(crops) * 1000
        embs[name] = E
        results[name] = {"top1": measure(E, y)[0], "ms_per_photo": ms}
        log(f"{name}: top-1 {results[name]['top1']:.3f}; {ms:.0f} ms a photo")
    agree = float(np.mean(np.sum(embs["fp32"] * embs["int8"], 1)))
    log(f"8-bit against full on the photos: mean cosine {agree:.4f}")

    # Which version ships: the small one unless it is clearly worse.
    r32, r8 = results["fp32"], results["int8"]
    use = "int8" if (r8["top1"] >= r32["top1"] - 0.015 and agree >= 0.97) else "fp32"
    src = os.path.join(BUILD, f"cow-reid-{use}.onnx")
    if os.path.getsize(src) > 95_000_000:
        log("The chosen model is over GitHub's 100 MB file limit: it can't be committed as one file.")
        use = "int8"
        src = os.path.join(BUILD, "cow-reid-int8.onnx")
    os.makedirs(os.path.join(REPO, "models"), exist_ok=True)
    shutil.copyfile(src, os.path.join(REPO, "models", "cow-reid.onnx"))
    unit = make_tuning.unit
    E = unit(embs[use].astype(np.float64))
    run = embedder(src, export["mean"], export["std"])

    # A look "a moment later" for every third photo.
    rng = np.random.RandomState(0)
    later = np.arange(0, len(crops), 3)
    L = []
    for i in later:
        img = cv2.imread(os.path.join(DATA, rels[i]))
        L.append(run(moment_later(img, boxes[i] if boxes[i] is not None else [0.0, 0.0, 1.0, 1.0], rng)))
    L = unit(np.stack(L).astype(np.float64))

    # The tuning that ships: how looks of one animal differ, from every barn photo (by cow) and the field looks (by track).
    np.savez_compressed(os.path.join(BUILD, "barn-looks.npz"), emb=E.astype(np.float32), cow=y.astype(np.int32))
    tuning, nb, nf = make_tuning.build(E, y)
    make_tuning.write(tuning, os.path.join(REPO, "models", "cow-tuning.bin"))
    log(f"tuning: {tuning['basis'].shape[0]} directions from {nb} barn and {nf} field looks")

    f_emb, f_clip, f_frame, f_group = make_tuning.field_looks()
    f_rows_all = make_tuning.within(f_emb, f_group)

    def tuning_without(test):
        """A tuning made without the barn cows being tested."""
        wb = make_tuning.within(E[~test], y[~test])
        rows = np.vstack([wb, f_rows_all * np.sqrt(len(wb) / len(f_rows_all))])
        return make_tuning.fit(rows, (E[~test].mean(0) + f_emb.mean(0)) / 2)

    # Barn: tested five lots of cows in turn, each with a tuning made without them.
    cow_ids = np.unique(y)
    order = np.random.RandomState(1).permutation(cow_ids)
    pos = np.zeros(len(y), int)
    for c in cow_ids:
        idx = np.where(y == c)[0]
        pos[idx] = np.arange(len(idx))
    herd5 = pos < 5
    barn = {}
    for method in ("plain", "tuned"):
        hits = np.zeros(len(y), bool)
        tables = {"moment": ([], []), "day": ([], [])}
        for k in range(5):
            test = np.isin(y, order[k::5])
            t = tuning_without(test) if method == "tuned" else None
            see = (lambda x, t=t: make_tuning.apply(t, x)) if t is not None else unit
            e = see(E)
            S = e @ e.T
            np.fill_diagonal(S, -9)
            hits[test] = (y[S.argmax(1)] == y)[test]
            # A moment later: the herd knows every cow from all its photos.
            m = np.isin(y[later], order[k::5])
            tables["moment"][0].append(by_cow(see(L[m]) @ e.T, y, cow_ids))
            tables["moment"][1].append(np.searchsorted(cow_ids, y[later][m]))
            # Another day: the herd knows each cow from its first five photos; every later photo is one look.
            probe = test & ~herd5
            tables["day"][0].append(by_cow(e[probe] @ e[herd5].T, y[herd5], cow_ids))
            tables["day"][1].append(np.searchsorted(cow_ids, y[probe]))
        barn[method] = {"top1": float(hits.mean())}
        for kind, (cs, owns) in tables.items():
            barn[method][kind] = naming(np.vstack(cs), np.concatenate(owns))
            barn[method][kind]["looks"] = int(sum(len(o) for o in owns))
        log(f"barn, {method}: top-1 {barn[method]['top1']:.3f}; a moment later {barn[method]['moment']}; another day {barn[method]['day']}")

    # Field: each clip seen through a tuning made without that clip's looks.
    def auc(a, b):
        return float((a[:, None] > b[None, :]).mean())

    wb_all = make_tuning.within(E, y)
    field = {}
    for method in ("plain", "tuned"):
        same, other = [], []
        for c in sorted(set(f_clip.tolist())):
            m = f_clip == c
            if method == "tuned":
                wf = make_tuning.within(f_emb[~m], f_group[~m])
                t = make_tuning.fit(np.vstack([wb_all, wf * np.sqrt(len(wb_all) / len(wf))]), (E.mean(0) + f_emb[~m].mean(0)) / 2)
                F = make_tuning.apply(t, f_emb[m])
            else:
                F = f_emb[m]
            a, b = field_pairs(F, f_clip[m], f_frame[m], f_group[m])
            same.append(a)
            other.append(b)
        a = np.concatenate(same)
        b = np.concatenate(other)
        field[method] = {"same_pairs": len(a), "other_pairs": len(b), "same_mean": float(a.mean()), "same_p05": float(np.percentile(a, 5)),
                         "other_mean": float(b.mean()), "other_p95": float(np.percentile(b, 95)), "same_over": float((a >= MATCH).mean()),
                         "other_over": float((b >= MATCH).mean()), "auc": auc(a, b)}
        log(f"field, {method}: {field[method]}")

    cfg = {
        "key": f"megadescriptor-t-224-{use}-1",
        "name": "MegaDescriptor-T-224",
        "licence": "CC BY-NC 4.0 (non-commercial)",
        "source": "https://huggingface.co/BVRA/MegaDescriptor-T-224",
        "size": SIZE, "mean": export["mean"], "std": export["std"], "dim": export["dim"],
        "match": MATCH, "fresh": FRESH, "margin": MARGIN, "tuning": "cow-tuning.bin",
        "notes": "The bars are for looks seen through the tuning (tools/make_tuning.py); what they achieve is in docs/recognition.md.",
    }
    with open(os.path.join(REPO, "models", "cow-reid.json"), "w") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")
    log("ships:", use, json.dumps(cfg))

    # Test pictures: three photos each of the first eight cows the finder boxed every time.
    out = os.path.join(REPO, "tests", "assets", "cows")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    P = embs[use]
    expected = {"model": cfg["key"], "photos": []}
    picked = 0
    for ci, (cow, items) in enumerate(cows.items()):
        idx = [i for i in range(len(rels)) if labels[i] == ci and boxes[i] is not None]
        if len(idx) < 3:
            continue
        picked += 1
        for k, i in enumerate(idx[:3]):
            name = f"cow{picked:02d}_{'abc'[k]}.jpg"
            cv2.imwrite(os.path.join(out, name), cv2.imread(os.path.join(DATA, rels[i])), [cv2.IMWRITE_JPEG_QUALITY, 92])
            expected["photos"].append({
                "file": name, "cow": picked, "box": [round(float(v), 4) for v in boxes[i]],
                "embedding": [round(float(v), 5) for v in P[i]] if picked <= 2 else None,
            })
        if picked == 8:
            break
    with open(os.path.join(out, "expected.json"), "w") as f:
        json.dump(expected, f)
    with open(os.path.join(out, "README.md"), "w") as f:
        f.write(
            "# Barn photos\n\n"
            "Side-on photos of Holstein cows from \"Holstein Cattle Recognition\" (A. Bhole, O. Falzon, M. Biehl, G. Azzopardi; "
            "Dairy Campus, Leeuwarden), published under CC0 1.0: <https://doi.org/10.34894/O1ZBSA>.\n\n"
            "`cowNN_a.jpg`, `_b`, `_c` are three photos of cow NN. `expected.json` has, for each, where the cow finder boxes "
            "the cow and (for the first two cows) what the recognition model makes of it, as worked out in Python by "
            "`tools/eval_reid.py`: the app's tests check the Kotlin code gets the same.\n"
        )
    log("test photos:", picked, "cows")

    with open(os.path.join(BUILD, "eval.json"), "w") as f:
        json.dump({"photos": len(crops), "cows": len(cows), "boxed": found, "how_rgb": how, "agree_int8_fp32": agree,
                   "colour_scaling": best_norm, "colour_scaling_top1": norm_top1, "ships": use, "config": cfg, "results": results,
                   "barn": barn, "field": field, "tuning": {"directions": int(tuning["basis"].shape[0]), "barn_looks": nb, "field_looks": nf},
                   "export": export}, f, indent=1)

    def pct(v):
        return f"{v * 100:.0f}%"

    def pct1(v):
        return f"{v * 100:.1f}%"

    bp, bt = barn["plain"], barn["tuned"]
    fp, ft = field["plain"], field["tuned"]
    os.makedirs(os.path.join(REPO, "docs"), exist_ok=True)
    with open(os.path.join(REPO, "docs", "recognition.md"), "w") as f:
        f.write(f"""# How well the app tells cows apart

Measured by `tools/eval_reid.py` (run by the *Models* workflow) on a computer, not on a phone and not on
your herd. Read it as what to expect at best, not a promise.

## In short

- **Followed without a break, a cow keeps its name** whatever it looks like: that is tracking, not
  recognition, and it is the dependable part.
- **Seen again a moment later** (it left the picture and came back, or the app lost it behind another
  cow): in fields, looks of one cow are much more alike than looks of different cows, and the app mostly
  gets this right. In the barn photos it is right {pct(bt['moment']['right'])} of the time and otherwise
  usually says nothing rather than something wrong.
- **Seen again another day**: unproven in fields (there are no photos to measure it on), and poor in the
  barn photos: {pct(bt['day']['right'])} named right, {pct(bt['day']['wrong'])} named wrong, the rest not named
  (so the cow is learnt a second time, to be merged by hand in the Herd tab).
- A cow's two sides look different, and cows with plain coats look the same. Neither can be fixed by a
  better threshold.

## The parts

**The model.** MegaDescriptor-T-224 ({export['parameters'] / 1e6:.1f} million parameters,
<https://huggingface.co/BVRA/MegaDescriptor-T-224>, CC BY-NC 4.0), exported to ONNX. The app ships the
**{'8-bit' if use == 'int8' else 'full'}** version ({os.path.getsize(src) / 1e6:.0f} MB): on the barn photos
the two give the same answers (most alike other photo is the same cow: full {pct1(r32['top1'])}, 8-bit
{pct1(r8['top1'])}; their descriptions agree to {agree:.3f}). A photo takes {r32['ms_per_photo']:.0f} ms (full) or
{r8['ms_per_photo']:.0f} ms (8-bit) on GitHub's machine with two threads.

**The tuning** (`models/cow-tuning.bin`, made by `tools/make_tuning.py`). The model's description of a
picture changes with how the cow stands, how its box was cut and what's behind it, as well as with which
cow it is. The tuning scales down the {tuning['basis'].shape[0]} directions that looks of ONE animal vary most
along, worked out from {nb} barn photos (grouped by cow) and {nf} looks from field videos (grouped by
tracked cow). Everything below marked "tuned" used a tuning made without the cows (or the video) being
tested.

**The rule.** A look is named when it is at least {MATCH} like a cow in the herd and that cow is {MARGIN}
clear of the next most alike one. A cow less than {FRESH} like every cow in the herd is new. In between,
the app waits for more looks (up to eight), then learns it as new. The "How sure" setting scales the
{MARGIN}.

## In fields

{len(set(f_clip.tolist()))} videos from Wikimedia Commons of cows in fields and yards (Holsteins, red-and-white
dairy cows, Montbéliardes, Belted Galloways; listed in `tools/clips.json`), played through the app's own
cow finder and tracker. {len(f_emb)} looks of {len(set(f_group.tolist()))} tracked cows.

| | Plain | Tuned |
|---|---|---|
| Two looks of one tracked cow, 2 s or more apart: how alike (average; lowest 5%) | {fp['same_mean']:.2f}; {fp['same_p05']:.2f} | {ft['same_mean']:.2f}; {ft['same_p05']:.2f} |
| Looks of two cows in view together: how alike (average; highest 5%) | {fp['other_mean']:.2f}; {fp['other_p95']:.2f} | {ft['other_mean']:.2f}; {ft['other_p95']:.2f} |
| One cow's pair is the more alike of the two (chance: 50%) | {pct1(fp['auc'])} | {pct1(ft['auc'])} |
| Pairs of one cow at least {MATCH} alike | | {pct(ft['same_over'])} |
| Pairs of different cows at least {MATCH} alike | | {pct1(ft['other_over'])} |

({fp['same_pairs']} pairs of one cow, {fp['other_pairs']} pairs of different cows.) These are looks seconds apart in
the same light: it says the app can tell a cow it has just seen from the others around it. It says nothing
about knowing a cow again next week.

## In the barn

{len(crops)} side-on colour photos of {len(cows)} Holstein cows, about {np.mean(counts):.0f} each, taken on different
days ("Holstein Cattle Recognition", Bhole, Falzon, Biehl, Azzopardi; Dairy Campus Leeuwarden; CC0;
<https://doi.org/10.34894/O1ZBSA>). A hard test, and not what the app is for: each cow stands in a stall
behind metal rails with its top half hidden by a banner, so a picture of it is mostly rails, legs and
belly, and the camera was moved between days. The cow finder boxed the cow in {found} of them
({pct1(found / len(crops))}); the others were used whole.

| | Plain | Tuned |
|---|---|---|
| The most alike other photo (of {len(crops) - 1}) is the same cow | {pct1(bp['top1'])} | {pct1(bt['top1'])} |
| **A moment later** (the same photo, its box moved a little, lighter or darker, smaller; {bt['moment']['looks']} looks): named right | {pct(bp['moment']['right'])} | {pct(bt['moment']['right'])} |
| ... named wrong | {pct1(bp['moment']['wrong'])} | {pct1(bt['moment']['wrong'])} |
| ... a cow that isn't in the herd given another cow's name | {pct1(bp['moment']['stranger_named'])} | {pct1(bt['moment']['stranger_named'])} |
| **Another day** (the herd knows each cow from its first five photos; {bt['day']['looks']} later photos, one look each): named right | {pct(bp['day']['right'])} | {pct(bt['day']['right'])} |
| ... named wrong | {pct1(bp['day']['wrong'])} | {pct1(bt['day']['wrong'])} |
| ... not named (it would be learnt again as a new cow) | {pct(bp['day']['unnamed'])} | {pct(bt['day']['unnamed'])} |
| ... a cow that isn't in the herd given another cow's name | {pct1(bp['day']['stranger_named'])} | {pct1(bt['day']['stranger_named'])} |

In the app a cow gets up to eight looks before it's named and its entry holds up to twelve, gathered as
it moves; here each look stood alone, which is harder.

## What was tried

- Bigger and other models, on the barn photos (most alike other photo is the same cow, plain):
  MegaDescriptor-T 33%, -S 42%, -B 47%, -L-384 50% (eighteen times slower); DINOv2 ViT-S 41%, ViT-B 37%;
  plain ImageNet features 35%. None is good at it, and after tuning the small ones are level.
- Scaling down everything the looks vary along (not only how one animal's looks vary): better in the barn,
  worse than no tuning in fields, where most of what varies is which cow it is.
- Matching small patches between two pictures (XFeat): in the barn it matches the rails.
""")
    log("wrote docs/recognition.md")


if __name__ == "__main__":
    main()
