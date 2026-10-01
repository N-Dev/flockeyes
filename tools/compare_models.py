#!/usr/bin/env python3
"""Compares recognition models on the side-on Holstein photos, to choose which one the app should use.

Each candidate describes every photo (the cow as the app's cow finder boxes it, or the whole photo where it
finds none); then, for every photo, is the most alike other photo one of the same cow (top-1)? Also how far
apart the same cow's photos and different cows' photos are, and how long a photo takes.

Run by hand: the Models workflow's "compare" job (Actions > Models > Run workflow > compare).
Usage: compare_models.py BUILD_DIR [DATA_DIR]      Needs torch and timm.
"""
import json
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import cowdetect  # noqa: E402
import eval_reid as ev  # noqa: E402

BUILD = ev.BUILD
DATA = ev.DATA
IMAGENET = ([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
HALF = ([0.5, 0.5, 0.5], [0.5, 0.5, 0.5])

# name, timm id, input size, colour scaling, extra arguments
CANDIDATES = [
    ("MegaDescriptor-T-224 (Swin-T, 28M)", "hf-hub:BVRA/MegaDescriptor-T-224", 224, HALF, {}),
    ("MegaDescriptor-T-224, ImageNet scaling", "hf-hub:BVRA/MegaDescriptor-T-224", 224, IMAGENET, {}),
    ("MegaDescriptor-S-224 (Swin-S, 50M)", "hf-hub:BVRA/MegaDescriptor-S-224", 224, HALF, {}),
    ("MegaDescriptor-B-224 (Swin-B, 88M)", "hf-hub:BVRA/MegaDescriptor-B-224", 224, HALF, {}),
    ("MegaDescriptor-L-384 (Swin-L, 228M)", "hf-hub:BVRA/MegaDescriptor-L-384", 384, HALF, {}),
    ("MegaDescriptor-T-CNN-288 (EfficientNet)", "hf-hub:BVRA/MegaDescriptor-T-CNN-288", 288, HALF, {}),
    ("DINOv2 ViT-S/14 (22M, Apache 2.0)", "vit_small_patch14_dinov2.lvd142m", 224, IMAGENET, {"img_size": 224}),
    ("DINOv2 ViT-B/14 (87M, Apache 2.0)", "vit_base_patch14_dinov2.lvd142m", 224, IMAGENET, {"img_size": 224}),
    ("MobileNetV3 ImageNet features (what the web version uses)", "mobilenetv3_large_100.ra_in1k", 224, IMAGENET, {}),
]


def main():
    import cv2
    import timm
    import torch

    torch.set_num_threads(os.cpu_count() or 2)
    os.makedirs(BUILD, exist_ok=True)
    ev.download(DATA)
    files = [f for f in ev.scan(DATA) if f[3] == "rgb"]
    cows = {}
    for rel, w, h, k in files:
        cows.setdefault(ev.identity(rel), []).append(rel)
    det = cowdetect.session(os.path.join(REPO, "models", "cows-tiny.onnx"))
    boxed, whole, labels = [], [], []
    found = 0
    for ci, (cow, items) in enumerate(cows.items()):
        for rel in items:
            img = cv2.imread(os.path.join(DATA, rel))
            if img is None:
                continue
            dets = cowdetect.detect(det, img, conf=0.25)
            h, w = img.shape[:2]
            crop = img
            if dets:
                b, s = max(dets, key=lambda d: (d[0][2] - d[0][0]) * (d[0][3] - d[0][1]))
                x1, y1, x2, y2 = int(b[0] * w), int(b[1] * h), int(np.ceil(b[2] * w)), int(np.ceil(b[3] * h))
                if x2 - x1 >= 24 and y2 - y1 >= 24:
                    crop = img[y1:y2, x1:x2]
                    found += 1
            boxed.append(crop)
            whole.append(img)
            labels.append(ci)
    y = np.array(labels)
    ev.log(f"{len(boxed)} photos of {len(cows)} cows; the cow finder boxed {found}")

    rows = []
    for name, tid, size, (mean, std), extra in CANDIDATES:
        try:
            model = timm.create_model(tid, pretrained=True, num_classes=0, **extra).eval()
        except Exception as e:  # noqa: BLE001
            ev.log(f"{name}: not available ({repr(e)[:160]})")
            rows.append({"name": name, "error": repr(e)[:200]})
            continue
        params = sum(p.numel() for p in model.parameters())
        m = torch.tensor(mean).view(1, 3, 1, 1)
        s = torch.tensor(std).view(1, 3, 1, 1)

        def embed(crops):
            out = []
            t0 = time.time()
            with torch.no_grad():
                for i in range(0, len(crops), 16):
                    batch = [cv2.cvtColor(cv2.resize(c, (size, size), interpolation=cv2.INTER_LINEAR), cv2.COLOR_BGR2RGB) for c in crops[i:i + 16]]
                    x = torch.from_numpy(np.stack(batch)).permute(0, 3, 1, 2).float() / 255.0
                    e = model((x - m) / s)
                    out.append(torch.nn.functional.normalize(e, dim=1).numpy())
            return np.concatenate(out), (time.time() - t0) / len(crops) * 1000

        row = {"name": name, "id": tid, "size": size, "params_m": round(params / 1e6, 1)}
        for label, crops in (("boxed", boxed), ("whole", whole)):
            E, ms = embed(crops)
            top1, genuine, impostor, has_mate = ev.measure(E, y)
            match, tpr, eer, ident, _ = ev.thresholds(genuine, impostor, has_mate)
            row[label] = {
                "top1": top1, "match": match, "kept": tpr, "named_right": ident, "eer": (1 - eer[1] + eer[2]) / 2,
                "genuine_mean": float(genuine.mean()), "impostor_mean": float(impostor.mean()), "ms": ms, "dim": int(E.shape[1]),
            }
            ev.log(f"{name} [{label}]: top-1 {top1:.3f}; at threshold {match:.2f}: {tpr:.3f} kept, {ident:.3f} named right; "
                   f"genuine {genuine.mean():.3f} impostor {impostor.mean():.3f}; {ms:.0f} ms a photo; {params / 1e6:.1f}M parameters")
        rows.append(row)
        with open(os.path.join(BUILD, "compare.json"), "w") as f:
            json.dump({"photos": len(boxed), "cows": len(cows), "boxed": found, "rows": rows}, f, indent=1)
        del model

    def pct(v):
        return f"{v * 100:.1f}%"

    lines = [
        "# Recognition models compared", "",
        f"{len(boxed)} side-on photos of {len(cows)} Holstein cows behind rails (CC0, <https://doi.org/10.34894/O1ZBSA>). "
        f"The cow finder boxed the cow in {found}. Top-1: the most alike other photo is the same cow.", "",
        "| Model | Parameters | Top-1, cow boxed | Top-1, whole photo | Named right at the 2% threshold (boxed) | Time a photo |",
        "|---|---|---|---|---|---|",
    ]
    for r in rows:
        if "error" in r:
            lines.append(f"| {r['name']} | | not available | | | |")
        else:
            lines.append(f"| {r['name']} | {r['params_m']}M | {pct(r['boxed']['top1'])} | {pct(r['whole']['top1'])} | {pct(r['boxed']['named_right'])} | {r['boxed']['ms']:.0f} ms |")
    lines += ["", "Times are PyTorch on GitHub's machine, for comparing the models with each other, not what a phone takes.", ""]
    with open(os.path.join(BUILD, "compare.md"), "w") as f:
        f.write("\n".join(lines))
    ev.log("\n".join(lines))


if __name__ == "__main__":
    main()
