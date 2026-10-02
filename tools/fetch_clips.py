#!/usr/bin/env python3
"""Downloads freely licensed videos of cows from Wikimedia Commons (tools/clips.json) and packs frames of them.

For each clip: its frames at 5 a second, 1280 wide, as JPEGs in OUT/clips.zip (NAME/0001.jpg ...), with
index.json saying where each came from, who made it and under which licence; and a contact sheet
(OUT/NAME.jpg) to see what's in it. Run by the Research workflow ("clips").

Usage: fetch_clips.py OUT_DIR
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else "build/clips"
API = "https://commons.wikimedia.org/w/api.php"
UA = {"User-Agent": "FlockEyes research (https://github.com/N-Dev/flockeyes; cow counting app tests)"}
FPS = 5
WIDTH = 1280


def api(params):
    url = API + "?" + urllib.parse.urlencode(params)
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            print("  retrying", repr(e)[:120], flush=True)
            time.sleep(3 * (i + 1))
    return {}


def strip(html):
    import re

    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", html or "")).strip()


def download(url, path):
    r = subprocess.run(["curl", "-fsSL", "--retry", "4", "--retry-all-errors", "--connect-timeout", "30", "--max-time", "900",
                        "-A", UA["User-Agent"], "-o", path, url], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip()[-300:])


def frames_ffmpeg(src, dest):
    r = subprocess.run(["ffmpeg", "-v", "error", "-i", src, "-vf", f"fps={FPS},scale={WIDTH}:-2", "-q:v", "4", os.path.join(dest, "%04d.jpg")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip()[-300:])


def frames_opencv(src, dest):
    import cv2

    cap = cv2.VideoCapture(src)
    fps = cap.get(cv2.CAP_PROP_FPS) or 25
    step = fps / FPS
    n = 0
    k = 0
    nxt = 0.0
    while True:
        ok, img = cap.read()
        if not ok:
            break
        if n >= nxt:
            k += 1
            h, w = img.shape[:2]
            img = cv2.resize(img, (WIDTH, int(round(h * WIDTH / w / 2)) * 2), interpolation=cv2.INTER_AREA)
            cv2.imwrite(os.path.join(dest, f"{k:04d}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 86])
            nxt += step
        n += 1
    if k == 0:
        raise RuntimeError("no frames read")


def main():
    import cv2
    import numpy as np

    os.makedirs(OUT, exist_ok=True)
    clips = json.load(open(os.path.join(HERE, "clips.json")))
    index = []
    have_ffmpeg = shutil.which("ffmpeg") is not None
    print("ffmpeg:", have_ffmpeg, flush=True)
    for c in clips:
        d = api({"action": "query", "format": "json", "titles": c["title"], "prop": "videoinfo", "viprop": "url|size|mime|extmetadata"})
        page = next(iter((d.get("query", {}).get("pages", {}) or {}).values()), {})
        vi = (page.get("videoinfo") or [{}])[0]
        if not vi.get("url"):
            print("not found:", c["title"])
            continue
        em = vi.get("extmetadata", {})
        src = os.path.join(OUT, c["name"] + os.path.splitext(vi["url"])[1])
        dest = os.path.join(OUT, c["name"])
        os.makedirs(dest, exist_ok=True)
        try:
            download(vi["url"], src)
            try:
                if not have_ffmpeg:
                    raise RuntimeError("no ffmpeg")
                frames_ffmpeg(src, dest)
            except Exception as e:  # noqa: BLE001
                print("  ffmpeg:", repr(e)[:160], "- trying OpenCV")
                frames_opencv(src, dest)
        except Exception as e:  # noqa: BLE001
            print("failed:", c["title"], repr(e)[:200])
            continue
        finally:
            if os.path.exists(src):
                os.remove(src)
        names = sorted(os.listdir(dest))
        info = {"name": c["name"], "title": c["title"], "page": vi.get("descriptionurl"), "licence": strip(em.get("LicenseShortName", {}).get("value")),
                "author": strip(em.get("Artist", {}).get("value")), "credit": strip(em.get("Credit", {}).get("value"))[:200],
                "width": vi.get("width"), "height": vi.get("height"), "seconds": vi.get("duration"), "fps": FPS, "frames": len(names)}
        index.append(info)
        print(json.dumps(info), flush=True)
        # A contact sheet: a frame every two seconds.
        tiles = []
        for n in names[::FPS * 2][:24]:
            img = cv2.resize(cv2.imread(os.path.join(dest, n)), (320, 180))
            cv2.putText(img, n[:4], (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
            tiles.append(img)
        while len(tiles) % 6:
            tiles.append(np.zeros((180, 320, 3), np.uint8))
        cv2.imwrite(os.path.join(OUT, c["name"] + ".jpg"), np.vstack([np.hstack(tiles[r:r + 6]) for r in range(0, len(tiles), 6)]), [cv2.IMWRITE_JPEG_QUALITY, 80])
        time.sleep(1)
    with open(os.path.join(OUT, "index.json"), "w") as f:
        json.dump(index, f, indent=1)
    with zipfile.ZipFile(os.path.join(OUT, "clips.zip"), "w", zipfile.ZIP_STORED) as z:
        z.write(os.path.join(OUT, "index.json"), "index.json")
        for info in index:
            d = os.path.join(OUT, info["name"])
            for n in sorted(os.listdir(d)):
                z.write(os.path.join(d, n), f"{info['name']}/{n}")
    print("clips.zip: %.0f MB, %d clips, %d frames" % (os.path.getsize(os.path.join(OUT, "clips.zip")) / 1e6, len(index), sum(i["frames"] for i in index)))


if __name__ == "__main__":
    main()
