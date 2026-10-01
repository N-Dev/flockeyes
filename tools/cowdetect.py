"""The app's cow finder in Python (the same steps as android/core's CowDetector.kt), for the tools.

YOLOX (COCO classes) on a 416 x 416 input: the picture in the top-left corner, grey padding, BGR, 0-255.
"""
import numpy as np

SIZE = 416
COW = 19
LOOKALIKES = (17, 18)  # horse, sheep


def _grid():
    rows = []
    for s in (8, 16, 32):
        g = SIZE // s
        for y in range(g):
            for x in range(g):
                rows.append((x, y, s))
    return np.array(rows, dtype=np.float32)


GRID = _grid()


def session(path, threads=2):
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


def iou(a, b):
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    if w <= 0 or h <= 0:
        return 0.0
    i = w * h
    return i / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i)


def detect(sess, bgr, conf=0.3, lookalikes=True):
    """Cows in a BGR picture (H x W x 3, uint8): a list of (box, score), box as fractions x1, y1, x2, y2."""
    import cv2

    fh, fw = bgr.shape[:2]
    scale = min(SIZE / fw, SIZE / fh)
    nw = min(SIZE, max(1, int(np.floor(fw * scale + 0.5))))
    nh = min(SIZE, max(1, int(np.floor(fh * scale + 0.5))))
    inp = np.full((SIZE, SIZE, 3), 114, dtype=np.float32)
    inp[:nh, :nw] = cv2.resize(bgr, (nw, nh), interpolation=cv2.INTER_LINEAR)
    out = sess.run(None, {sess.get_inputs()[0].name: inp.transpose(2, 0, 1)[None]})[0][0]
    obj = out[:, 4]
    cls = out[:, 5:]
    cow = cls[:, COW]
    if lookalikes:
        for c in LOOKALIKES:
            cow = np.maximum(cow, cls[:, c])
    score = obj * cow
    keep = np.where((obj >= conf) & (score >= conf))[0]
    cx = (out[keep, 0] + GRID[keep, 0]) * GRID[keep, 2]
    cy = (out[keep, 1] + GRID[keep, 1]) * GRID[keep, 2]
    bw = np.exp(out[keep, 2]) * GRID[keep, 2]
    bh = np.exp(out[keep, 3]) * GRID[keep, 2]
    rx = nw / fw
    ry = nh / fh
    boxes = np.stack([(cx - bw / 2) / rx / fw, (cy - bh / 2) / ry / fh, (cx + bw / 2) / rx / fw, (cy + bh / 2) / ry / fh], 1)
    boxes = np.clip(boxes, 0, 1)
    dets = sorted(zip(boxes.tolist(), score[keep].tolist()), key=lambda d: -d[1])
    kept = []
    for b, s in dets:
        if all(iou(k[0], b) <= 0.5 for k in kept):
            kept.append((b, s))
    return kept
