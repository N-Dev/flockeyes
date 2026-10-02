#!/usr/bin/env python3
"""Looks on Wikimedia Commons for freely licensed videos of cows, to measure the app on real footage.

Writes OUT/videos.tsv (title, size, length, licence, author, link) and contact sheets of their preview
frames (OUT/sheet-N.jpg), so that suitable ones can be picked by eye. Run by the Research workflow.

Usage: find_videos.py OUT_DIR
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else "build/videos"
API = "https://commons.wikimedia.org/w/api.php"
UA = {"User-Agent": "FlockEyes research (https://github.com/N-Dev/flockeyes; cow counting app tests)"}
QUERIES = [
    "Holstein cows", "dairy cows walking", "cows grazing", "cows in a field", "cows pasture", "cattle herd", "dairy cattle",
    "Holstein Friesian", "cows walking", "Kühe Weide", "Kühe", "koeien", "koeien wei", "vaches", "vaches pré", "cow herd milking",
    "cows going to milking", "cows road", "Rinder Weide", "vacas", "cattle grazing", "cows meadow", "Holstein-Rinder", "cows gate",
]


def get(params):
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


def main():
    import cv2
    import numpy as np

    os.makedirs(OUT, exist_ok=True)
    found = {}
    for q in QUERIES:
        cont = {}
        for page in range(3):
            d = get({"action": "query", "format": "json", "generator": "search", "gsrsearch": f"{q} filetype:video", "gsrnamespace": 6,
                     "gsrlimit": 50, "prop": "videoinfo", "viprop": "url|size|mime|extmetadata", "viurlwidth": 480, **cont})
            for p in (d.get("query", {}).get("pages", {}) or {}).values():
                vi = (p.get("videoinfo") or [{}])[0]
                if not vi.get("url"):
                    continue
                em = vi.get("extmetadata", {})
                found.setdefault(p["title"], {
                    "title": p["title"], "url": vi["url"], "thumb": vi.get("thumburl"), "w": vi.get("width"), "h": vi.get("height"),
                    "bytes": vi.get("size"), "seconds": vi.get("duration"), "mime": vi.get("mime"),
                    "licence": strip(em.get("LicenseShortName", {}).get("value")), "author": strip(em.get("Artist", {}).get("value"))[:80],
                    "about": strip(em.get("ImageDescription", {}).get("value"))[:160], "page": vi.get("descriptionurl"), "query": q,
                })
            cont = d.get("continue") or {}
            if not cont:
                break
        print(q, "->", len(found), "videos so far", flush=True)
    vids = sorted(found.values(), key=lambda v: v["title"])
    # Worth a look: big enough to see a cow, and not hours long.
    vids = [v for v in vids if (v["w"] or 0) >= 640 and 4 <= (v["seconds"] or 0) <= 600]
    with open(os.path.join(OUT, "videos.json"), "w") as f:
        json.dump(vids, f, indent=1)
    with open(os.path.join(OUT, "videos.tsv"), "w") as f:
        f.write("n\ttitle\tsize\tseconds\tMB\tlicence\tauthor\tabout\tpage\n")
        for i, v in enumerate(vids):
            f.write(f"{i}\t{v['title']}\t{v['w']}x{v['h']}\t{v['seconds']:.0f}\t{(v['bytes'] or 0) / 1e6:.1f}\t{v['licence']}\t{v['author']}\t{v['about']}\t{v['page']}\n")
    print(len(vids), "videos listed")

    tiles = []
    for i, v in enumerate(vids):
        tile = np.full((200, 320, 3), 40, np.uint8)
        try:
            with urllib.request.urlopen(urllib.request.Request(v["thumb"], headers=UA), timeout=60) as r:
                img = cv2.imdecode(np.frombuffer(r.read(), np.uint8), cv2.IMREAD_COLOR)
            s = min(320 / img.shape[1], 180 / img.shape[0])
            img = cv2.resize(img, (int(img.shape[1] * s), int(img.shape[0] * s)))
            tile[:img.shape[0], :img.shape[1]] = img
        except Exception as e:  # noqa: BLE001
            print("  no preview for", v["title"], repr(e)[:80])
        cv2.putText(tile, f"{i}: {v['seconds']:.0f}s {v['w']}x{v['h']}", (4, 196), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
        tiles.append(tile)
        time.sleep(0.2)
    per = 30
    for s in range(0, len(tiles), per):
        chunk = tiles[s:s + per]
        while len(chunk) % 6:
            chunk.append(np.full((200, 320, 3), 40, np.uint8))
        rows = [np.hstack(chunk[r:r + 6]) for r in range(0, len(chunk), 6)]
        cv2.imwrite(os.path.join(OUT, f"sheet-{s // per}.jpg"), np.vstack(rows), [cv2.IMWRITE_JPEG_QUALITY, 80])
    print("sheets:", (len(tiles) + per - 1) // per)


if __name__ == "__main__":
    main()
