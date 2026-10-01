#!/usr/bin/env python3
"""Measures the cow recognition model on side-on photos of Holstein cows, and sets the app's thresholds.

The photos: "Holstein Cattle Recognition" (Bhole et al., Dairy Campus Leeuwarden; 136 cows, about nine
RGB photos each; CC0; https://doi.org/10.34894/O1ZBSA), downloaded from DataverseNL.

For every photo the app's own steps are followed: the cow finder (YOLOX) boxes the cow, the box is squashed
to 224 x 224 and the recognition model describes it. Then:

  * top-1: is the most alike other photo one of the same cow?
  * for every photo, how alike is its best match among the SAME cow's other photos ("genuine") and among
    all OTHER cows' photos ("impostor")? The app's "same cow" threshold is put where only 2% of impostors
    would pass.

Writes models/cow-reid.onnx (the version that ships), models/cow-reid.json (how to feed it, and the
thresholds), docs/recognition.md (the results) and a few of the photos as test pictures (tests/assets/cows).

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


def thresholds(genuine, impostor_all, has_mate):
    impostor = impostor_all
    rows = []
    for t in np.arange(0.10, 0.96, 0.01):
        rows.append((round(float(t), 2), float((genuine >= t).mean()), float((impostor >= t).mean())))
    # Where at most 2% of photos have some other cow this alike.
    match = next((t for t, _, fpr in rows if fpr <= 0.02), rows[-1][0])
    tpr = next(tp for t, tp, _ in rows if t == match)
    eer = min(rows, key=lambda r: abs((1 - r[1]) - r[2]))
    # Right cow, and over the threshold.
    ident = float(((genuine >= match) & (genuine > impostor[has_mate])).mean())
    return match, tpr, eer, ident, rows


def hist(v, lo=-0.2, hi=1.0, bins=24):
    h, edges = np.histogram(np.clip(v, lo, hi), bins=bins, range=(lo, hi))
    return [(round(float(edges[i]), 2), int(h[i])) for i in range(bins)]


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
        top1, genuine, impostor, has_mate = measure(E, y)
        match, tpr, eer, ident, rows = thresholds(genuine, impostor, has_mate)
        results[name] = {
            "top1": top1, "match": match, "tpr_at_match": tpr, "identified_at_match": ident,
            "eer_threshold": eer[0], "eer": (1 - eer[1] + eer[2]) / 2, "ms_per_photo": ms,
            "genuine_mean": float(genuine.mean()), "impostor_mean": float(impostor.mean()),
            "genuine_p05": float(np.percentile(genuine, 5)), "impostor_p95": float(np.percentile(impostor, 95)),
            "genuine_hist": hist(genuine), "impostor_hist": hist(impostor), "rows": rows,
        }
        log(f"{name}: top-1 {top1:.3f}; threshold {match:.2f} (2% of impostors pass) keeps {tpr:.3f} of genuine, "
            f"{ident:.3f} named right; genuine mean {genuine.mean():.3f}, impostor mean {impostor.mean():.3f}; {ms:.0f} ms a photo")
    agree = float(np.mean(np.sum(embs["fp32"] * embs["int8"], 1)))
    log(f"8-bit against full on the photos: mean cosine {agree:.4f}")

    # Only the boxed photos (as in the app, which never looks at a cow it hasn't boxed).
    boxed = np.array([b is not None for b in boxes])
    if 0 < boxed.sum() < len(boxes):
        for name in ("fp32", "int8"):
            t1, _, _, _ = measure(embs[name][boxed], y[boxed])
            results[name]["top1_boxed_only"] = t1
            log(f"{name}: top-1 on boxed photos only {t1:.3f}")

    # Which version ships: the small one unless it is clearly worse.
    r32, r8 = results["fp32"], results["int8"]
    use = "int8" if (r8["top1"] >= r32["top1"] - 0.015 and agree >= 0.97) else "fp32"
    chosen = results[use]
    src = os.path.join(BUILD, f"cow-reid-{use}.onnx")
    if os.path.getsize(src) > 95_000_000:
        log("The chosen model is over GitHub's 100 MB file limit: it can't be committed as one file.")
        use = "int8"
        chosen = results[use]
        src = os.path.join(BUILD, "cow-reid-int8.onnx")
    os.makedirs(os.path.join(REPO, "models"), exist_ok=True)
    shutil.copyfile(src, os.path.join(REPO, "models", "cow-reid.onnx"))
    match = chosen["match"]
    cfg = {
        "key": f"megadescriptor-t-224-{use}-1",
        "name": "MegaDescriptor-T-224",
        "licence": "CC BY-NC 4.0 (non-commercial)",
        "source": "https://huggingface.co/BVRA/MegaDescriptor-T-224",
        "size": SIZE, "mean": export["mean"], "std": export["std"], "dim": export["dim"],
        "match": match, "fresh": round(match - 0.08, 2), "margin": 0.04,
        "notes": f"Thresholds from {len(crops)} side-on photos of {len(cows)} Holstein cows (doi:10.34894/O1ZBSA): "
                 f"top-1 {chosen['top1'] * 100:.1f}%.",
    }
    with open(os.path.join(REPO, "models", "cow-reid.json"), "w") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")
    log("ships:", use, json.dumps(cfg))

    # Test pictures: three photos each of the first eight cows the finder boxed every time.
    out = os.path.join(REPO, "tests", "assets", "cows")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    E = embs[use]
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
                "embedding": [round(float(v), 5) for v in E[i]] if picked <= 2 else None,
            })
        if picked == 8:
            break
    with open(os.path.join(out, "expected.json"), "w") as f:
        json.dump(expected, f)
    with open(os.path.join(out, "README.md"), "w") as f:
        f.write(
            "# Test photos\n\n"
            "Side-on photos of Holstein cows from \"Holstein Cattle Recognition\" (A. Bhole, O. Falzon, M. Biehl, G. Azzopardi; "
            "Dairy Campus, Leeuwarden), published under CC0 1.0: <https://doi.org/10.34894/O1ZBSA>.\n\n"
            "`cowNN_a.jpg`, `_b`, `_c` are three photos of cow NN. `expected.json` has, for each, where the cow finder boxes "
            "the cow and (for the first two cows) what the recognition model makes of it, as worked out in Python by "
            "`tools/eval_reid.py`: the app's tests check the Kotlin code gets the same.\n"
        )
    log("test photos:", picked, "cows")

    with open(os.path.join(BUILD, "eval.json"), "w") as f:
        json.dump({"photos": len(crops), "cows": len(cows), "boxed": found, "how_rgb": how, "agree_int8_fp32": agree,
                   "colour_scaling": best_norm, "colour_scaling_top1": norm_top1,
                   "ships": use, "config": cfg, "results": results, "export": export}, f, indent=1)

    def pct(v):
        return f"{v * 100:.1f}%"

    def bars(h):
        top = max(c for _, c in h) or 1
        return "\n".join(f"    {lo:5.2f} {'#' * int(round(40 * c / top))} {c}" for lo, c in h if c)

    os.makedirs(os.path.join(REPO, "docs"), exist_ok=True)
    with open(os.path.join(REPO, "docs", "recognition.md"), "w") as f:
        f.write(f"""# How well the recognition model tells cows apart

Measured by `tools/eval_reid.py` (run by the *Models* workflow), not on a phone.

**The photos.** {len(crops)} side-on colour photos of {len(cows)} Holstein cows, about {np.mean(counts):.0f} each, from
"Holstein Cattle Recognition" (Bhole, Falzon, Biehl, Azzopardi; Dairy Campus Leeuwarden; CC0;
<https://doi.org/10.34894/O1ZBSA>). They are 640 x 480, taken indoors with the cow behind metal rails. The app's cow finder boxed the cow in {found} of them ({pct(found / len(crops))}); the others
were used whole.

**The model.** MegaDescriptor-T-224 ({export['parameters'] / 1e6:.1f} million parameters), exported to ONNX. The app ships the
**{'8-bit' if use == 'int8' else 'full'}** version ({os.path.getsize(src) / 1e6:.0f} MB).

| | Full (32-bit, {export['fp32_bytes'] / 1e6:.0f} MB) | 8-bit ({export['int8_bytes'] / 1e6:.0f} MB) |
|---|---|---|
| Most alike other photo is the same cow (top-1) | {pct(r32['top1'])} | {pct(r8['top1'])} |
| "Same cow" threshold (2% of other cows pass) | {r32['match']:.2f} | {r8['match']:.2f} |
| Photos whose own cow passes it | {pct(r32['tpr_at_match'])} | {pct(r8['tpr_at_match'])} |
| Photos named right at that threshold | {pct(r32['identified_at_match'])} | {pct(r8['identified_at_match'])} |
| Time a photo (2 threads, GitHub's machine) | {r32['ms_per_photo']:.0f} ms | {r8['ms_per_photo']:.0f} ms |

The two versions agree closely on these photos (mean cosine {agree:.3f}).

**What this does and doesn't show.** Each cow's photos here were taken in the same place, side-on, at the
same distance and from the same side, partly hidden by rails. That is close to the gate count's
situation. Some of a cow's photos may have been taken moments apart, which is easier than knowing a cow
again days later. A field is harder: cows are further away, at every angle, and hide each other. And a
cow's two sides have different markings, so a cow learnt from its left looks like a stranger from its
right until the app has seen both.

## How alike photos are (cosine, {use})

Best match among the same cow's other photos:

{bars(chosen['genuine_hist'])}

Best match among every other cow's photos:

{bars(chosen['impostor_hist'])}
""")
    log("wrote docs/recognition.md")


if __name__ == "__main__":
    main()
