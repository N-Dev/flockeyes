#!/usr/bin/env python3
"""Fetches the cow recognition model and turns it into the file the app uses.

MegaDescriptor-T-224 (https://huggingface.co/BVRA/MegaDescriptor-T-224, CC BY-NC 4.0) is a Swin-Tiny
network trained to tell individual animals apart, cattle included. This script:

  1. downloads it with timm,
  2. exports it to ONNX (one 224 x 224 picture in, a 768-number description out),
  3. checks ONNX Runtime gives the same answers as PyTorch,
  4. makes a smaller version with 8-bit weights (about a quarter of the size),
  5. writes both to OUT (default build/reid).

Run by .github/workflows/models.yml (GitHub's machines can reach Hugging Face).
Needs: torch, timm, onnx, onnxruntime, numpy.
"""
import json
import os
import sys
import time

import numpy as np

OUT = sys.argv[1] if len(sys.argv) > 1 else "build/reid"
NAME = "hf-hub:BVRA/MegaDescriptor-T-224"
SIZE = 224


def main():
    os.makedirs(OUT, exist_ok=True)
    os.environ.setdefault("TIMM_FUSED_ATTN", "0")  # plain attention maths, which exports cleanly
    import timm
    import torch
    import onnx
    import onnxruntime as ort

    print("torch", torch.__version__, "timm", timm.__version__, "onnx", onnx.__version__, "onnxruntime", ort.__version__, flush=True)
    model = timm.create_model(NAME, pretrained=True)
    model.eval()
    params = sum(p.numel() for p in model.parameters())
    cfg = getattr(model, "pretrained_cfg", {}) or {}
    print("parameters:", params, "num_features:", getattr(model, "num_features", None))
    print("pretrained_cfg:", {k: cfg.get(k) for k in ("input_size", "mean", "std", "interpolation", "crop_pct", "num_classes")})

    torch.manual_seed(0)
    x = torch.rand(1, 3, SIZE, SIZE) * 2 - 1
    with torch.no_grad():
        ref = model(x)
    print("output shape:", tuple(ref.shape))
    assert ref.ndim == 2 and ref.shape[0] == 1, "expected one description per picture"
    dim = int(ref.shape[1])

    fp32 = os.path.join(OUT, "cow-reid-fp32.onnx")
    kwargs = dict(input_names=["image"], output_names=["embedding"], opset_version=17, do_constant_folding=True)
    try:
        torch.onnx.export(model, x, fp32, dynamo=False, **kwargs)
    except TypeError:
        torch.onnx.export(model, x, fp32, **kwargs)
    onnx.checker.check_model(fp32)
    print("exported", fp32, os.path.getsize(fp32), "bytes", flush=True)

    def session(path):
        so = ort.SessionOptions()
        so.intra_op_num_threads = 2
        return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])

    def cos(a, b):
        a = a.ravel()
        b = b.ravel()
        return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))

    s32 = session(fp32)
    got = s32.run(None, {"image": x.numpy()})[0]
    c = cos(got, ref.numpy())
    print("ONNX against PyTorch: cosine %.6f" % c)
    assert c > 0.9999, "the exported model doesn't match"

    # A smaller copy: the weights of the matrix multiplications as 8-bit numbers.
    from onnxruntime.quantization import QuantType, quantize_dynamic
    from onnxruntime.quantization.shape_inference import quant_pre_process

    pre = os.path.join(OUT, "cow-reid-pre.onnx")
    try:
        quant_pre_process(fp32, pre, skip_symbolic_shape=True)
    except Exception as e:  # not essential
        print("pre-processing skipped:", repr(e))
        pre = fp32
    int8 = os.path.join(OUT, "cow-reid-int8.onnx")
    quantize_dynamic(pre, int8, weight_type=QuantType.QUInt8, op_types_to_quantize=["MatMul", "Gemm"])
    onnx.checker.check_model(int8)
    print("quantised", int8, os.path.getsize(int8), "bytes", flush=True)
    s8 = session(int8)

    # Agreement between the two on random pictures (real pictures are compared in eval_reid.py).
    rng = np.random.default_rng(1)
    cs = []
    for _ in range(8):
        r = (rng.random((1, 3, SIZE, SIZE), dtype=np.float32) * 2 - 1)
        cs.append(cos(s32.run(None, {"image": r})[0], s8.run(None, {"image": r})[0]))
    print("8-bit against full: cosine min %.4f mean %.4f" % (min(cs), float(np.mean(cs))))

    def bench(s, n=6):
        r = np.zeros((1, 3, SIZE, SIZE), dtype=np.float32)
        s.run(None, {"image": r})
        t0 = time.time()
        for _ in range(n):
            s.run(None, {"image": r})
        return (time.time() - t0) / n * 1000

    info = {
        "source": NAME,
        "architecture": "swin_tiny_patch4_window7_224",
        "parameters": params,
        "dim": dim,
        "size": SIZE,
        "mean": list(map(float, cfg.get("mean", (0.5, 0.5, 0.5)))),
        "std": list(map(float, cfg.get("std", (0.5, 0.5, 0.5)))),
        "fp32_bytes": os.path.getsize(fp32),
        "int8_bytes": os.path.getsize(int8),
        "onnx_vs_torch_cosine": c,
        "int8_vs_fp32_cosine_random": [min(cs), float(np.mean(cs))],
        "ms_fp32_2threads": bench(s32),
        "ms_int8_2threads": bench(s8),
        "ops_int8": sorted({n.op_type for n in onnx.load(int8).graph.node}),
        "versions": {"torch": torch.__version__, "timm": timm.__version__, "onnx": onnx.__version__, "onnxruntime": ort.__version__},
    }
    with open(os.path.join(OUT, "export.json"), "w") as f:
        json.dump(info, f, indent=2)
    print(json.dumps(info, indent=2))
    if pre != fp32:
        os.remove(pre)


if __name__ == "__main__":
    main()
