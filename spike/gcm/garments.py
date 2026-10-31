"""Per-garment measurement. docs/02-domain/garments/lld.md."""
import math
from dataclasses import dataclass

import numpy as np

from . import colour as C
from .estimate import clipped_dark
from .labels import GARMENT_CODES, NAMES


@dataclass
class Garment:
    code: int
    fraction: float
    bounds: tuple
    linear: np.ndarray
    xyz: np.ndarray
    lab: np.ndarray
    hex: str
    clipped: bool

    @property
    def name(self):
        return NAMES[self.code]


def measure(img, labels, matrix):
    h, w = labels.shape
    n_all = h * w
    clipped, dark = clipped_dark(img)
    out = []
    for code in GARMENT_CODES:
        p = labels == code
        n_p = int(p.sum())
        if n_p == 0 or n_p < max(30, 0.005 * n_all):
            continue
        ys, xs = np.nonzero(p)
        bounds = (xs.min() / w, ys.min() / h, (xs.max() + 1) / w, (ys.max() + 1) / h)
        q = p & ~clipped & ~dark
        if int(q.sum()) >= max(30, 0.5 * n_p):
            was_clipped = False
        else:
            q = p & ~dark
            was_clipped = True
        if int(q.sum()) == 0:
            continue
        px = img[q]
        y = C.luminance(px)
        ys_sorted = np.sort(y)
        n = len(ys_sorted)
        lo = ys_sorted[int(math.floor(0.10 * (n - 1)))]
        hi = ys_sorted[int(math.floor(0.90 * (n - 1)))]
        mean = px[(y >= lo) & (y <= hi)].mean(axis=0)
        rgb = np.maximum(matrix @ mean, 0.0)
        xyz = C.M @ rgb
        out.append(Garment(code, n_p / n_all, bounds, rgb, xyz, C.xyz_to_lab(xyz),
                           C.hex_of(rgb), was_clipped))
    out.sort(key=lambda g: (-g.fraction, g.code))
    return out
