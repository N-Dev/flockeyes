#!/usr/bin/env python3
"""Makes the tuning the app ships with (models/cow-tuning.bin): how looks of ONE cow differ from each other.

The recognition model's description of a picture changes with how the cow stands, how its box was cut and
what is behind it, as well as with which cow it is. The tuning finds the directions looks of one cow vary
along (from groups of looks known to be of one animal) and scales those down, so that what is left says
more about which cow it is. It is the "within-class whitening" used in face and speaker recognition.

The groups:
  * the barn photos, grouped by cow (136 Holsteins, about nine photos each on different days; CC0,
    doi:10.34894/O1ZBSA): BARN, an .npz with `emb` (N x D, unit length) and `cow` (N), written by
    eval_reid.py;
  * looks the app's own pipeline took of cows it was tracking in videos of cows in fields, grouped by
    track (tools/data/field-looks.f32 and .tsv; the videos are listed in tools/clips.json).
Both count the same, however many looks each has.

Usage: make_tuning.py BARN.npz OUT.bin
"""
import os
import struct
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
DIRECTIONS = 64
SHRINK = 0.3


def unit(x):
    return x / (np.linalg.norm(x, axis=-1, keepdims=True) + 1e-12)


def within(emb, groups):
    """Each look less the middle of its group's looks: what's left is how looks of one animal differ."""
    rows = []
    for g in sorted(set(groups.tolist())):
        x = emb[groups == g]
        if len(x) >= 2:
            rows.append(x - x.mean(0))
    return np.vstack(rows)


def fit(rows, mean, directions=DIRECTIONS, shrink=SHRINK):
    """The directions the rows vary most along, how much (spreads), and what to scale each by (gains)."""
    n, d = rows.shape
    w, v = np.linalg.eigh(rows.T @ rows / n)
    o = np.argsort(-w)
    w = np.maximum(w[o], 0)
    v = v[:, o]
    k = min(directions, n - 1, d)
    average = w.sum() / d
    rest = w[k:].mean() if k < d else 0.0
    rest_scale = 1 / np.sqrt(rest + shrink * average)
    gains = (1 / np.sqrt(w[:k] + shrink * average)) / rest_scale - 1
    return {"mean": mean.astype(np.float32), "basis": v[:, :k].T.astype(np.float32), "spreads": w[:k].astype(np.float32),
            "gains": gains.astype(np.float32), "rest": float(rest), "average": float(average), "rows": int(n)}


def apply(t, x):
    """A description as the tuning sees it (the same sums as Tuning.apply in the app)."""
    xc = x - t["mean"]
    a = xc @ t["basis"].T
    return unit(xc + (a * t["gains"]) @ t["basis"])


def write(t, path):
    k, d = t["basis"].shape
    with open(path, "wb") as f:
        f.write(b"FETU")
        f.write(struct.pack("<iiii", 1, d, k, t["rows"]))
        f.write(struct.pack("<ff", t["average"], t["rest"]))
        f.write(t["mean"].astype("<f4").tobytes())
        f.write(t["spreads"].astype("<f4").tobytes())
        f.write(t["gains"].astype("<f4").tobytes())
        f.write(t["basis"].astype("<f4").tobytes())


def read(path):
    b = open(path, "rb").read()
    assert b[:4] == b"FETU"
    _, d, k, rows = struct.unpack_from("<iiii", b, 4)
    average, rest = struct.unpack_from("<ff", b, 20)
    o = 28
    mean = np.frombuffer(b, "<f4", d, o); o += 4 * d
    spreads = np.frombuffer(b, "<f4", k, o); o += 4 * k
    gains = np.frombuffer(b, "<f4", k, o); o += 4 * k
    basis = np.frombuffer(b, "<f4", k * d, o).reshape(k, d)
    return {"mean": mean, "basis": basis, "spreads": spreads, "gains": gains, "rest": rest, "average": average, "rows": rows}


def field_looks():
    rows = [l.rstrip("\n").split("\t") for l in open(os.path.join(HERE, "data", "field-looks.tsv"))]
    emb = np.fromfile(os.path.join(HERE, "data", "field-looks.f32"), dtype="<f4").reshape(len(rows), -1).astype(np.float64)
    clip = np.array([r[0] for r in rows])
    frame = np.array([int(r[2]) for r in rows])
    group = np.array([f"{r[0]}:{r[3]}" for r in rows])
    return unit(emb), clip, frame, group


def build(barn_emb, barn_cow):
    f_emb, _, _, f_group = field_looks()
    wb = within(barn_emb, barn_cow)
    wf = within(f_emb, f_group)
    # The barn has many more looks than the field: weigh the two alike.
    rows = np.vstack([wb, wf * np.sqrt(len(wb) / len(wf))])
    mean = (barn_emb.mean(0) + f_emb.mean(0)) / 2
    return fit(rows, mean), len(wb), len(wf)


def main():
    z = np.load(sys.argv[1])
    t, nb, nf = build(unit(z["emb"].astype(np.float64)), z["cow"])
    write(t, sys.argv[2])
    print(f"tuning: {t['basis'].shape[0]} directions of {t['basis'].shape[1]}, from {nb} barn and {nf} field looks; "
          f"gains {t['gains'][0]:.2f} .. {t['gains'][-1]:.2f}; {os.path.getsize(sys.argv[2])} bytes", flush=True)


if __name__ == "__main__":
    main()
