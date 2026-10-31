"""The light. docs/02-domain/illuminant/lld.md — this is its reference.

img is h × w × 3, linear, float64. labels is h × w, uint8 label codes.
Every answer is a unit vector, or None when the estimator does not answer.
"""
import math
from dataclasses import dataclass, field

import numpy as np

from . import colour as C
from .labels import BACKGROUND, HAIR, SKIN, SCENE, SKIN_ROLE, roles

CLIP = 0.95
DARK = 0.005
P = 6.0
ESTIMATORS = ("GRAY_WORLD", "SHADES_OF_GRAY", "GRAY_EDGE", "SKIN")

_K = np.exp(-(np.arange(-3, 4, dtype=np.float64) ** 2) / 2.0)
KERNEL = _K / _K.sum()

# ColorChecker dark skin and light skin, 8-bit sRGB (§3).
_SKINS = C.DECODE_LUT[np.array([[115, 82, 68], [194, 150, 130]])]
SKIN_K = (_SKINS / _SKINS.sum(axis=1, keepdims=True)).mean(axis=0)


@dataclass
class Estimate:
    answers: dict = field(default_factory=dict)  # name -> unit vector or None
    combined: object = None
    disagreement: object = None
    support: float = 0.0
    score: float = 0.0
    level: str = "LOW"
    reason: object = None


def normalise(v):
    v = np.asarray(v, dtype=np.float64)
    if not np.all(np.isfinite(v)):
        return None
    n = math.sqrt(float(v @ v))
    if not math.isfinite(n) or n <= 0:
        return None
    return v / n


def clipped_dark(img):
    mx = img.max(axis=2)
    return mx >= CLIP, mx < DARK


def usable_mask(img, labels):
    clipped, dark = clipped_dark(img)
    r = roles(labels)
    return ((r == SCENE) | (r == SKIN_ROLE)) & ~clipped & ~dark


def region_weights(labels, usable):
    n = int(usable.sum())
    w = np.zeros(labels.shape, dtype=np.float64)
    for region in (BACKGROUND, HAIR, SKIN):
        m = usable & (labels == region)
        n_r = int(m.sum())
        if n_r:
            w[m] = min(n_r / n, 0.5) / n_r
    return w


def gray_world(img, w):
    return (img * w[..., None]).sum(axis=(0, 1)) / w.sum()


def shades_of_gray(img, w):
    return ((img ** P * w[..., None]).sum(axis=(0, 1)) / w.sum()) ** (1.0 / P)


def blur(plane):
    h, w = plane.shape
    p = np.pad(plane, ((0, 0), (3, 3)), mode="edge")
    tmp = np.zeros((h, w))
    for i in range(7):
        tmp = tmp + KERNEL[i] * p[:, i:i + w]
    p = np.pad(tmp, ((3, 3), (0, 0)), mode="edge")
    out = np.zeros((h, w))
    for i in range(7):
        out = out + KERNEL[i] * p[i:i + h, :]
    return out


def gradient_magnitude(b):
    p = np.pad(b, 1, mode="edge")
    gx = (p[1:-1, 2:] - p[1:-1, :-2]) / 2.0
    gy = (p[2:, 1:-1] - p[:-2, 1:-1]) / 2.0
    return np.sqrt(gx * gx + gy * gy)


def erode(mask, r=4):
    """Chebyshev erosion; neighbours outside the image are ignored."""
    out = mask.copy()
    for k in range(1, r + 1):
        out[:, :-k] &= mask[:, k:]
        out[:, k:] &= mask[:, :-k]
    tmp = out
    out = tmp.copy()
    for k in range(1, r + 1):
        out[:-k, :] &= tmp[k:, :]
        out[k:, :] &= tmp[:-k, :]
    return out


def gray_edge(img, usable, w):
    e_mask = erode(usable, 4)
    if int(e_mask.sum()) < 200:
        return None
    m = np.stack([gradient_magnitude(blur(img[..., c])) for c in range(3)], axis=-1)
    m_e = m[e_mask]
    w_e = w[e_mask]
    if float(m_e.max()) < 1e-9:
        return None
    return normalise(((m_e ** P * w_e[:, None]).sum(axis=0) / w_e.sum()) ** (1.0 / P))


def skin_anchor(img, labels, usable):
    n_all = labels.size
    s = usable & (labels == SKIN)
    if int(s.sum()) < max(300, 0.005 * n_all):
        return None
    return normalise(img[s].mean(axis=0) / SKIN_K)


def estimate(img, labels):
    n_all = labels.size
    usable = usable_mask(img, labels)
    n = int(usable.sum())
    support = n / n_all
    none = {k: None for k in ESTIMATORS}
    if n < max(500, 0.02 * n_all):
        return Estimate(none, None, None, support, 0.0, "LOW", "NO_SCENE")

    w = region_weights(labels, usable)
    answers = {
        "GRAY_WORLD": normalise(gray_world(img, w)),
        "SHADES_OF_GRAY": normalise(shades_of_gray(img, w)),
        "GRAY_EDGE": gray_edge(img, usable, w),
        "SKIN": skin_anchor(img, labels, usable),
    }
    voters = [answers[k] for k in ("SHADES_OF_GRAY", "GRAY_EDGE", "SKIN") if answers[k] is not None]
    if not voters:
        return Estimate(answers, None, None, support, 0.0, "LOW", "NO_SCENE")

    total = np.zeros(3)
    for a in voters:
        total = total + a
    combined = normalise(total)
    d = 0.0
    for i in range(len(voters)):
        for j in range(i + 1, len(voters)):
            d = max(d, C.angle(voters[i], voters[j]))

    agree = min(1.0, max(0.0, 1.0 - d / 6.0))
    enough = min(1.0, max(0.0, support / 0.15))
    # One estimator alone can never be confident: there is nothing to disagree with.
    voices = 1.0 if len(voters) >= 2 else 0.45
    score = agree * enough * voices
    level = "HIGH" if score >= 0.5 else "LOW"
    reason = None
    if level == "LOW":
        if support < 0.075:
            reason = "LITTLE_SCENE"
        elif d > 3.0:
            reason = "DISAGREE"
        elif len(voters) < 2:
            reason = "ONE_ESTIMATOR"
        else:
            reason = "DISAGREE" if agree <= enough else "LITTLE_SCENE"
    return Estimate(answers, combined, d, support, score, level, reason)


def neutral(img, u, v):
    """§6. Returns ("ok", unit vector) or ("refused", "TOO_BRIGHT" | "TOO_DARK")."""
    h, w = img.shape[:2]
    cx = min(max(int(math.floor(u * w)), 0), w - 1)
    cy = min(max(int(math.floor(v * h)), 0), h - 1)
    r = max(2, int(math.floor(0.01 * max(w, h) + 0.5)))
    patch = img[max(0, cy - r):min(h, cy + r + 1), max(0, cx - r):min(w, cx + r + 1)].reshape(-1, 3)
    mx = patch.max(axis=1)
    clipped = mx >= CLIP
    dark = mx < DARK
    valid = ~clipped & ~dark
    if 2 * int(valid.sum()) < len(patch):
        return ("refused", "TOO_BRIGHT" if int(clipped.sum()) > int(dark.sum()) else "TOO_DARK")
    return ("ok", normalise(np.median(patch[valid], axis=0)))
