#!/usr/bin/env python3
"""Packs what's needed to try recognition methods away from GitHub's machines.

The workspace the app is written in can't reach Hugging Face or the photo archive, but it can download
this repository's release files. So this script, run by the Research workflow, makes:

  * holstein-rgb.zip: the colour photos of the 136 Holstein cows (CC0, doi:10.34894/O1ZBSA), named
    cNNN_KK.jpg (cow, photo), with index.tsv saying which file each came from;
  * candidate models exported to ONNX with their inner outputs (every patch's description, not only the
    summary), so that ways of comparing two pictures can be tried without exporting again.

Usage: research.py OUT_DIR [DATA_DIR]      Needs torch, timm, onnx, onnxruntime.
"""
import json
import os
import subprocess
import sys
import zipfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import eval_reid as ev  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "build/research"
DATA = sys.argv[2] if len(sys.argv) > 2 else "build/data"


def pack_photos():
    files = [f for f in ev.scan(DATA) if f[3] == "rgb"]
    cows = {}
    for rel, w, h, k in files:
        cows.setdefault(ev.identity(rel), []).append(rel)
    path = os.path.join(OUT, "holstein-rgb.zip")
    lines = []
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for ci, (cow, items) in enumerate(cows.items()):
            for k, rel in enumerate(sorted(items)):
                name = f"c{ci + 1:03d}_{k + 1:02d}.jpg"
                z.write(os.path.join(DATA, rel), name)
                lines.append(f"{name}\t{ci + 1}\t{rel}")
        z.writestr("index.tsv", "\n".join(lines) + "\n")
    ev.log(f"packed {len(lines)} photos of {len(cows)} cows: {os.path.getsize(path) / 1e6:.1f} MB")


def export(model, x, name, outputs):
    import onnx
    import onnxruntime as ort
    import torch

    path = os.path.join(OUT, name)
    with torch.no_grad():
        ref = model(x)
    ref = ref if isinstance(ref, (tuple, list)) else (ref,)
    kwargs = dict(input_names=["image"], output_names=outputs, opset_version=17, do_constant_folding=True)
    try:
        torch.onnx.export(model, x, path, dynamo=False, **kwargs)
    except TypeError:
        torch.onnx.export(model, x, path, **kwargs)
    onnx.checker.check_model(path)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 2
    got = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"]).run(None, {"image": x.numpy()})
    worst = 0.0
    for r, g in zip(ref, got):
        worst = max(worst, float(np.abs(r.numpy() - g).max()))
    ev.log(f"exported {name}: {os.path.getsize(path) / 1e6:.1f} MB, outputs {[tuple(r.shape) for r in ref]}, "
           f"largest difference from PyTorch {worst:.5f}")
    return {"file": name, "bytes": os.path.getsize(path), "outputs": {o: list(r.shape) for o, r in zip(outputs, ref)},
            "input": list(x.shape), "max_abs_diff": worst}


def main():
    os.makedirs(OUT, exist_ok=True)
    os.environ.setdefault("TIMM_FUSED_ATTN", "0")
    import timm
    import torch

    ev.download(DATA)
    pack_photos()
    done = []

    class Features(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x):
            return self.m.forward_features(x)

    torch.manual_seed(0)
    # name, timm id, (height, width), output name, extra arguments
    jobs = [
        ("dinov2-s14-224.onnx", "vit_small_patch14_dinov2.lvd142m", (224, 224), "tokens", {"img_size": (224, 224)}),
        ("dinov2-s14-224x336.onnx", "vit_small_patch14_dinov2.lvd142m", (224, 336), "tokens", {"img_size": (224, 336)}),
        ("dinov2-s14-reg-224.onnx", "vit_small_patch14_reg4_dinov2.lvd142m", (224, 224), "tokens", {"img_size": (224, 224)}),
        ("dinov2-s14-reg-224x336.onnx", "vit_small_patch14_reg4_dinov2.lvd142m", (224, 336), "tokens", {"img_size": (224, 336)}),
        ("dinov2-b14-224.onnx", "vit_base_patch14_dinov2.lvd142m", (224, 224), "tokens", {"img_size": (224, 224)}),
        ("megadescriptor-t-224-map.onnx", "hf-hub:BVRA/MegaDescriptor-T-224", (224, 224), "map", {}),
        ("megadescriptor-s-224-map.onnx", "hf-hub:BVRA/MegaDescriptor-S-224", (224, 224), "map", {}),
        ("megadescriptor-b-224-map.onnx", "hf-hub:BVRA/MegaDescriptor-B-224", (224, 224), "map", {}),
    ]
    for name, tid, (h, w), out, extra in jobs:
        try:
            model = timm.create_model(tid, pretrained=True, num_classes=0, **extra).eval()
            info = export(Features(model), torch.rand(1, 3, h, w) * 2 - 1, name, [out])
            info["timm"] = tid
            info["parameters"] = sum(p.numel() for p in model.parameters())
            cfg = getattr(model, "pretrained_cfg", {}) or {}
            info["mean"], info["std"] = list(cfg.get("mean", [])), list(cfg.get("std", []))
            done.append(info)
            del model
        except Exception as e:  # noqa: BLE001
            ev.log(f"{name}: failed ({repr(e)[:300]})")
            done.append({"file": name, "error": repr(e)[:300]})

    # XFeat (Apache 2.0): a small network that finds points in a picture and describes each one, for
    # matching the same points between two pictures.
    try:
        src = os.path.join(OUT, "accelerated_features")
        if not os.path.exists(src):
            subprocess.run(["git", "clone", "--depth", "1", "https://github.com/verlab/accelerated_features", src], check=True)
        sys.path.insert(0, src)
        from modules.model import XFeatModel

        net = XFeatModel().eval()
        net.load_state_dict(torch.load(os.path.join(src, "weights", "xfeat.pt"), map_location="cpu"))

        def unfold2d(x, ws=2):  # the same as the original's, written with reshapes (which export)
            b, c, h, w = x.shape
            x = x.reshape(b, c, h // ws, ws, w // ws, ws).permute(0, 1, 3, 5, 2, 4)
            return x.reshape(b, c * ws * ws, h // ws, w // ws)

        x = torch.rand(1, 3, 256, 384)
        with torch.no_grad():
            before = net(x)
        net._unfold2d = unfold2d
        with torch.no_grad():
            after = net(x)
        ev.log("xfeat: reshaped unfold differs by", max(float((a - b).abs().max()) for a, b in zip(before, after)))
        for (h, w) in ((256, 384), (320, 480), (224, 224)):
            info = export(net, torch.rand(1, 3, h, w), f"xfeat-{h}x{w}.onnx", ["feats", "keypoints", "heatmap"])
            info["parameters"] = sum(p.numel() for p in net.parameters())
            done.append(info)
        rev = subprocess.run(["git", "-C", src, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        done.append({"xfeat_commit": rev})
    except Exception as e:  # noqa: BLE001
        ev.log(f"xfeat: failed ({repr(e)[:400]})")
        done.append({"file": "xfeat", "error": repr(e)[:400]})

    with open(os.path.join(OUT, "research.json"), "w") as f:
        json.dump(done, f, indent=1)
    ev.log(json.dumps(done, indent=1))


if __name__ == "__main__":
    main()
