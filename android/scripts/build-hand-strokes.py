#!/usr/bin/env python3
"""Build the online handwriting template used by HandStrokes.kt.

Each GB2312 character becomes an 8×8×8 direction histogram of its stroke
medians (Make Me a Hanzi, y flipped so down is positive). The Kotlin
recognizer must compute the same histogram from touch points.
"""

import json
import math
import random
import struct
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
CHARS_PATH = ROOT / "android/app/src/main/assets/hand-chars.txt"
OUT_PATH = ROOT / "android/app/src/main/assets/hand-strokes.bin"
GRID = 8
BOX = 64
DIRS = 8
DIM = GRID * GRID * DIRS


def feature(strokes):
    pts = [p for s in strokes for p in s]
    hist = np.zeros(DIM, np.float64)
    if len(pts) < 2:
        return hist
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    minx, maxx = min(xs), max(xs)
    miny, maxy = min(ys), max(ys)
    width = max(maxx - minx, 1.0)
    height = max(maxy - miny, 1.0)
    scale = 48.0 / max(width, height)
    ox = (BOX - width * scale) / 2.0
    oy = (BOX - height * scale) / 2.0

    def cell_of(x, y):
        gx = int(x) // 8
        gy = int(y) // 8
        return min(7, max(0, gx)), min(7, max(0, gy))

    for stroke in strokes:
        if len(stroke) < 2:
            continue
        mapped = [((x - minx) * scale + ox, (y - miny) * scale + oy) for x, y in stroke]
        segs = []
        total = 0.0
        for a, b in zip(mapped, mapped[1:]):
            dist = math.hypot(b[0] - a[0], b[1] - a[1])
            if dist < 1e-4:
                continue
            segs.append((a, b, dist, total))
            total += dist
        if total < 1.0 or not segs:
            continue
        count = max(2, int(total / 2.0) + 1)
        samples = []
        si = 0
        for i in range(count):
            target = total * i / (count - 1)
            while si + 1 < len(segs) and segs[si][3] + segs[si][2] < target:
                si += 1
            a, b, dist, base = segs[si]
            t = (target - base) / dist if dist else 0.0
            t = min(1.0, max(0.0, t))
            samples.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
        for a, b in zip(samples, samples[1:]):
            dx, dy = b[0] - a[0], b[1] - a[1]
            length = math.hypot(dx, dy)
            if length < 1e-4:
                continue
            ang = math.atan2(dy, dx)
            dbin = int((ang + math.pi) / (2 * math.pi) * 8) % 8
            mx, my = (a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0
            gx, gy = cell_of(mx, my)
            hist[(gy * GRID + gx) * DIRS + dbin] += length
            hist[(gy * GRID + gx) * DIRS + ((dbin + 4) % 8)] += length * 0.25
    blurred = np.zeros_like(hist)
    kernel = np.array([[1, 2, 1], [2, 4, 2], [1, 2, 1]], dtype=np.float64)
    for direction in range(DIRS):
        plane = hist[direction::DIRS].reshape(GRID, GRID)
        pad = np.pad(plane, 1, mode="edge")
        acc = np.zeros((GRID, GRID))
        for y in range(GRID):
            for x in range(GRID):
                acc[y, x] = np.sum(pad[y : y + 3, x : x + 3] * kernel)
        blurred[direction::DIRS] = acc.reshape(-1)
    norm = np.linalg.norm(blurred)
    if norm > 0:
        blurred /= norm
    return blurred


def quantize(vec):
    return np.clip(np.rint(vec * 127.0), -127, 127).astype(np.int16)


def main():
    source = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/mmh.txt")
    chars = CHARS_PATH.read_text().strip()
    medians = {}
    with source.open() as handle:
        for line in handle:
            row = json.loads(line)
            medians.setdefault(row["character"], row["medians"])
    packed = []
    matrix = []
    missing = []
    for ch in chars:
        raw = medians.get(ch)
        if not raw:
            missing.append(ch)
            packed.append((0, np.zeros(DIM, np.int16)))
            matrix.append(np.zeros(DIM))
            continue
        strokes = [[(float(x), -float(y)) for x, y in stroke] for stroke in raw]
        vec = feature(strokes)
        matrix.append(vec)
        count = sum(1 for stroke in raw if len(stroke) >= 2)
        packed.append((count, quantize(vec)))
    matrix = np.stack(matrix)
    labels = list(chars)

    def rank(strokes):
        q = quantize(feature(strokes)).astype(np.float64)
        qn = np.dot(q, q)
        scores = []
        for index, (count, tmpl) in enumerate(packed):
            tn = float(np.dot(tmpl.astype(np.float64), tmpl.astype(np.float64)))
            if qn <= 0 or tn <= 0:
                scores.append(-1.0)
                continue
            dot = float(np.dot(q, tmpl.astype(np.float64)))
            scores.append(dot / math.sqrt(qn * tn))
        order = np.argsort(scores)[::-1][:5]
        return [(labels[int(i)], round(float(scores[int(i)]), 3)) for i in order]

    men = [[(float(x), -float(y)) for x, y in stroke] for stroke in medians["们"]]
    de = [[(float(x), -float(y)) for x, y in stroke] for stroke in medians["的"]]
    print("们", rank(men))
    print("的", rank(de))
    rng = random.Random(1)
    noisy = [[(x + rng.gauss(0, 18), y + rng.gauss(0, 18)) for x, y in stroke] for stroke in men]
    noisy_rank = rank(noisy)
    print("们 jitter", noisy_rank)
    if noisy_rank[0][0] != "们":
        raise SystemExit("jittered 们 was recognized as " + noisy_rank[0][0])
    if rank(de)[0][0] != "的":
        raise SystemExit("的 was not recognized")

    blob = bytearray()
    blob += b"HSF1"
    blob += struct.pack("<HH", len(chars), DIM)
    for count, tmpl in packed:
        norm2 = int(np.dot(tmpl.astype(np.int32), tmpl.astype(np.int32)))
        blob += struct.pack("<BI", count, norm2)
        blob += tmpl.astype(np.int8).tobytes()
    OUT_PATH.write_bytes(blob)
    print("wrote", OUT_PATH, "bytes", len(blob), "missing", "".join(missing))


if __name__ == "__main__":
    main()
