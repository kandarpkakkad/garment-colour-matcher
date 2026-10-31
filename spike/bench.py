"""The phase 0 benchmark. Accuracy LLD §5.

    python spike/bench.py [--seeds 10] [--size 128x96]
        Synthetic scenes with exact truth: every estimator, the naive baseline,
        garment ΔE2000 and family errors, by light, by kind, by confidence.

    python spike/bench.py --gt gt.csv --images DIR [--black N] [--saturation N]
        A public dataset of linear 16-bit PNGs with a measured illuminant per
        image. No garment masks: it measures the estimators, not the mitigation.
"""
import argparse
import csv
import math
import os
import sys

import numpy as np

from gcm import colour as C
from gcm.engine import analyse, linear
from gcm.estimate import ESTIMATORS, estimate, normalise
from gcm.families import is_family_error
from gcm.garments import measure
from gcm.scenes import KINDS, LIGHTS, make

METHODS = ("NAIVE_GRAY_WORLD",) + ESTIMATORS + ("COMBINED", "COMBINED_NO_SKIN")


def stats(xs):
    xs = np.asarray([x for x in xs if x is not None], dtype=np.float64)
    if len(xs) == 0:
        return "      -       -     n=0"
    return f"{np.median(xs):7.2f} {np.percentile(xs, 90):7.2f}   n={len(xs)}"


def synthetic(seeds, w, h):
    rows = []
    lights = list(LIGHTS) + ["mixed"]
    for light in lights:
        for kind in KINDS:
            if light == "mixed" and kind != "outfit":
                continue
            for i in range(seeds):
                seed = 10_000 + 97 * i + 7 * lights.index(light) + 3 * KINDS.index(kind)
                scene = make(seed, kind, light, w, h)
                img = linear(scene.img8)
                a = analyse(scene.img8, scene.labels)
                naive = normalise(img.reshape(-1, 3).mean(axis=0))
                truth_g = {g.code: g for g in measure(scene.d65, scene.labels, np.eye(3))}

                errors = {"NAIVE_GRAY_WORLD": C.angle(naive, scene.truth),
                          "COMBINED": None if a.estimate.combined is None
                          else C.angle(a.estimate.combined, scene.truth)}
                for k in ESTIMATORS:
                    v = a.estimate.answers.get(k)
                    errors[k] = None if v is None else C.angle(v, scene.truth)
                # Kept beside COMBINED until real skin settles the skin anchor's vote
                # (accuracy HLD, results).
                two = [a.estimate.answers[k] for k in ("SHADES_OF_GRAY", "GRAY_EDGE")
                       if a.estimate.answers.get(k) is not None]
                errors["COMBINED_NO_SKIN"] = C.angle(normalise(sum(two)), scene.truth) if two else None

                naive_g = {g.code: g for g in measure(img, scene.labels, C.correction_matrix(naive))}
                garments = []
                for g in a.garments:
                    t = truth_g.get(g.code)
                    n = naive_g.get(g.code)
                    if t is None or n is None:
                        continue
                    garments.append((C.delta_e2000(t.lab, g.lab), C.delta_e2000(t.lab, n.lab),
                                     is_family_error(t.lab, g.lab), is_family_error(t.lab, n.lab)))
                rows.append(dict(light=light, kind=kind, level=a.estimate.level,
                                 errors=errors, garments=garments))
    return rows


def report(rows):
    def table(title, group):
        print(f"\n{title} — angular error, degrees (median, p90)")
        keys = sorted({r[group] for r in rows}) if group else [None]
        for key in keys:
            sel = [r for r in rows if group is None or r[group] == key]
            print(f"  {str(key or 'all'):10s}")
            for m in METHODS:
                print(f"    {m:17s} {stats([r['errors'][m] for r in sel])}")

    table("Overall", None)
    table("By light", "light")
    table("By kind", "kind")

    g = [x for r in rows for x in r["garments"]]
    print("\nGarments — ΔE2000 against the truth under D65 (median, p90)")
    print(f"    combined          {stats([x[0] for x in g])}")
    print(f"    naive gray-world  {stats([x[1] for x in g])}")
    if g:
        print(f"    family errors     combined {100 * np.mean([x[2] for x in g]):.1f} %   "
              f"naive {100 * np.mean([x[3] for x in g]):.1f} %")

    print("\nBy confidence — garment ΔE2000, combined estimate")
    for level in ("HIGH", "LOW"):
        sel = [x for r in rows if r["level"] == level for x in r["garments"]]
        scenes = sum(1 for r in rows if r["level"] == level)
        print(f"    {level:5s} scenes={scenes:<4d} {stats([x[0] for x in sel])}")

    med = lambda m: np.median([r["errors"][m] for r in rows if r["errors"][m] is not None])
    print(f"\nPhase 0 gate (synthetic): combined median {med('COMBINED'):.2f}° "
          f"vs naive gray-world {med('NAIVE_GRAY_WORLD'):.2f}°")


def box_downscale(img, longest=512):
    h, w = img.shape[:2]
    f = max(1, math.ceil(max(h, w) / longest))
    h2, w2 = h // f, w // f
    return img[:h2 * f, :w2 * f].reshape(h2, f, w2, f, 3).mean(axis=(1, 3))


def dataset(gt, images, black, saturation):
    import cv2  # only needed here

    errs = {m: [] for m in ("NAIVE_GRAY_WORLD",) + ESTIMATORS + ("COMBINED",)}
    with open(gt, newline="") as f:
        for row in csv.reader(f):
            if not row or not row[1].strip().replace(".", "", 1).replace("e-", "", 1).isdigit():
                continue  # a header, or a blank line
            name = row[0].strip()
            path = os.path.join(images, name)
            if not os.path.splitext(path)[1]:
                path += ".png"
            raw = cv2.imread(path, cv2.IMREAD_UNCHANGED)
            if raw is None:
                raise SystemExit(f"cannot read {path}")
            rgb = raw[..., ::-1].astype(np.float64)
            img = np.clip((rgb - black) / (saturation - black), 0.0, 1.0)
            img = box_downscale(img)
            truth = normalise([float(row[1]), float(row[2]), float(row[3])])
            labels = np.zeros(img.shape[:2], dtype=np.uint8)
            est = estimate(img, labels)
            errs["NAIVE_GRAY_WORLD"].append(C.angle(normalise(img.reshape(-1, 3).mean(axis=0)), truth))
            for k in ESTIMATORS:
                v = est.answers.get(k)
                errs[k].append(None if v is None else C.angle(v, truth))
            errs["COMBINED"].append(None if est.combined is None else C.angle(est.combined, truth))

    print("Dataset — angular error, degrees")
    print(f"    {'method':17s} {'mean':>7s} {'median':>7s} {'best25':>7s} {'worst25':>7s}")
    for m, xs in errs.items():
        xs = np.sort([x for x in xs if x is not None])
        if len(xs) == 0:
            print(f"    {m:17s}       -")
            continue
        q = max(1, len(xs) // 4)
        print(f"    {m:17s} {xs.mean():7.2f} {np.median(xs):7.2f} {xs[:q].mean():7.2f} "
              f"{xs[-q:].mean():7.2f}   n={len(xs)}")
    return errs


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("--seeds", type=int, default=10)
    p.add_argument("--size", default="128x96")
    p.add_argument("--gt")
    p.add_argument("--images")
    p.add_argument("--black", type=float, default=0.0)
    p.add_argument("--saturation", type=float, default=65535.0)
    a = p.parse_args(argv)
    if a.gt:
        if not a.images:
            p.error("--gt needs --images")
        dataset(a.gt, a.images, a.black, a.saturation)
        return
    w, h = (int(v) for v in a.size.split("x"))
    report(synthetic(a.seeds, w, h))


if __name__ == "__main__":
    main(sys.argv[1:])
