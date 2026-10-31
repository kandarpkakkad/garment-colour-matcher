"""Synthetic scenes with exact ground truth. docs/04-accuracy/lld.md §2.

They prove the mechanism, not the product: a scene here is a Mondrian and a
figure made of rectangles. Real photos decide whether it works.
"""
from dataclasses import dataclass, field

import numpy as np

from . import colour as C
from .labels import BACKGROUND, DRESS, HAIR, SKIN, SKIRT, TOP, TROUSERS

CHECKER = [
    (115, 82, 68), (194, 150, 130), (98, 122, 157), (87, 108, 67), (133, 128, 177),
    (103, 189, 170), (214, 126, 44), (80, 91, 166), (193, 90, 99), (94, 60, 108),
    (157, 188, 64), (224, 163, 46), (56, 61, 150), (70, 148, 73), (175, 54, 60),
    (231, 199, 31), (187, 86, 149), (8, 133, 161), (243, 243, 242), (200, 200, 200),
    (160, 160, 160), (122, 122, 121), (85, 85, 85), (52, 52, 52),
]
WALLS = [(235, 230, 220), (200, 185, 160), (128, 128, 128), (140, 100, 70), (150, 165, 140)]
SKINS = [(241, 194, 167), (224, 172, 140), (198, 134, 105), (161, 110, 75), (120, 80, 55),
         (92, 57, 40)]
HAIRS = [(40, 30, 25), (80, 55, 35), (150, 110, 70), (20, 20, 20)]
GARMENTS = {
    "red": (200, 30, 40), "blue": (30, 60, 170), "green": (40, 140, 60),
    "navy": (30, 40, 80), "black": (25, 25, 28), "white": (235, 235, 232),
    "grey": (128, 128, 128), "burgundy": (110, 30, 45), "cream": (240, 230, 205),
    "olive": (110, 110, 60), "mustard": (210, 170, 50), "denim": (70, 95, 130),
    "pink": (230, 150, 170), "brown": (110, 75, 50),
}
SATURATED = ("red", "blue", "green")
NEUTRALS = ("black", "white", "navy", "grey", "cream")
EVERYDAY = tuple(GARMENTS)

LIGHTS = {
    "A": (0.4476, 0.4074),
    "LED3000": (0.4369, 0.4041),
    "F2": (0.3721, 0.3753),
    "D55": (0.3324, 0.3474),
    "D65": (0.3127, 0.3290),
    "D75": (0.2990, 0.3149),
}
KINDS = ("outfit", "dominant", "neutrals", "no-skin")


@dataclass
class Scene:
    img8: np.ndarray        # h × w × 3 uint8, as captured
    labels: np.ndarray      # h × w uint8
    truth: np.ndarray       # the light on the garments, unit linear sRGB
    d65: np.ndarray         # the same scene with no cast, same exposure, no noise
    kind: str
    light: str
    seed: int
    colours: dict = field(default_factory=dict)


def lin(c8):
    return C.DECODE_LUT[np.array(c8)]


def _grid(w, h):
    yy, xx = np.mgrid[0:h, 0:w]
    return xx + 0.5, yy + 0.5


def _rect(w, h, x0, y0, x1, y1):
    xx, yy = _grid(w, h)
    return (xx >= x0) & (xx < x1) & (yy >= y0) & (yy < y1)


def _ellipse(w, h, cx, cy, rx, ry):
    xx, yy = _grid(w, h)
    return ((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2 <= 1.0


def make(seed, kind, light, w=96, h=72, exposure=0.9, wall_to_wall=False):
    rng = np.random.default_rng(seed)
    refl = np.zeros((h, w, 3))
    labels = np.full((h, w), BACKGROUND, dtype=np.uint8)
    garment = np.zeros((h, w), dtype=bool)
    colours = {}

    palette = CHECKER + WALLS
    refl[:] = lin(WALLS[rng.integers(len(WALLS))])
    for _ in range(int(rng.integers(12, 21))):
        rw, rh = rng.uniform(0.1, 0.4) * w, rng.uniform(0.1, 0.4) * h
        x0, y0 = rng.uniform(-0.1 * w, w), rng.uniform(-0.1 * h, h)
        refl[_rect(w, h, x0, y0, x0 + rw, y0 + rh)] = lin(palette[rng.integers(len(palette))])

    skin = lin(SKINS[rng.integers(len(SKINS))])
    hair = lin(HAIRS[rng.integers(len(HAIRS))])
    _, yy = _grid(w, h)

    def paint(mask, code, colour):
        refl[mask] = colour
        labels[mask] = code
        garment[mask] = code not in (BACKGROUND, HAIR, SKIN)

    def head(cx, cy, rx, ry):
        paint(_ellipse(w, h, cx, cy - 0.25 * ry, rx * 1.2, ry * 1.15) & (yy < cy - 0.2 * ry), HAIR, hair)
        paint(_ellipse(w, h, cx, cy, rx, ry), SKIN, skin)

    cx = w * rng.uniform(0.45, 0.55)
    if kind in ("outfit", "neutrals"):
        names = NEUTRALS if kind == "neutrals" else EVERYDAY
        top = names[rng.integers(len(names))]
        bottom = names[rng.integers(len(names))]
        head(cx, 0.18 * h, 0.07 * w, 0.09 * h)
        sleeves = rng.random() < 0.5
        arms = (_rect(w, h, cx - 0.20 * w, 0.30 * h, cx - 0.14 * w, 0.62 * h)
                | _rect(w, h, cx + 0.14 * w, 0.30 * h, cx + 0.20 * w, 0.62 * h))
        paint(arms, TOP if sleeves else SKIN, lin(GARMENTS[top]) if sleeves else skin)
        paint(_rect(w, h, cx - 0.14 * w, 0.28 * h, cx + 0.14 * w, 0.60 * h), TOP, lin(GARMENTS[top]))
        colours[TOP] = top
        if rng.random() < 0.6:
            legs = (_rect(w, h, cx - 0.12 * w, 0.60 * h, cx - 0.01 * w, 0.97 * h)
                    | _rect(w, h, cx + 0.01 * w, 0.60 * h, cx + 0.12 * w, 0.97 * h))
            paint(legs, TROUSERS, lin(GARMENTS[bottom]))
            colours[TROUSERS] = bottom
        else:
            paint(_rect(w, h, cx - 0.15 * w, 0.60 * h, cx + 0.15 * w, 0.78 * h), SKIRT, lin(GARMENTS[bottom]))
            legs = (_rect(w, h, cx - 0.10 * w, 0.78 * h, cx - 0.03 * w, 0.97 * h)
                    | _rect(w, h, cx + 0.03 * w, 0.78 * h, cx + 0.10 * w, 0.97 * h))
            paint(legs, SKIN, skin)
            colours[SKIRT] = bottom
    elif kind == "dominant":
        name = SATURATED[rng.integers(len(SATURATED))]
        code = TOP if rng.random() < 0.5 else DRESS
        head(cx, 0.10 * h, 0.06 * w, 0.08 * h)
        mx = rng.uniform(0.06, 0.13)
        paint(_rect(w, h, mx * w, 0.19 * h, (1 - mx) * w, 0.97 * h), code, lin(GARMENTS[name]))
        colours[code] = name
    elif kind == "no-skin":
        top = EVERYDAY[rng.integers(len(EVERYDAY))]
        bottom = EVERYDAY[rng.integers(len(EVERYDAY))]
        paint(_rect(w, h, 0.30 * w, 0.0, 0.70 * w, 0.55 * h), TOP, lin(GARMENTS[top]))
        paint(_rect(w, h, 0.33 * w, 0.55 * h, 0.67 * w, h), TROUSERS, lin(GARMENTS[bottom]))
        colours[TOP], colours[TROUSERS] = top, bottom
    else:
        raise ValueError(kind)

    if wall_to_wall:
        name = SATURATED[rng.integers(len(SATURATED))]
        paint(_rect(w, h, 0, 0, w, h - 4), TOP, lin(GARMENTS[name]))
        colours = {TOP: name}

    # Shading: a smooth gradient over the frame, folds on garments, fabric texture.
    xx, _ = _grid(w, h)
    theta = rng.uniform(0, 2 * np.pi)
    t = (xx / w - 0.5) * np.cos(theta) + (yy / h - 0.5) * np.sin(theta)
    shade = np.clip(0.825 + 0.25 * t, 0.65, 1.0)
    period, phase = rng.uniform(6, 14), rng.uniform(0, 2 * np.pi)
    folds = np.where(garment, 0.75 + 0.25 * np.sin(2 * np.pi * xx / period + phase) ** 2, 1.0)
    texture = 1.0 + rng.uniform(-0.04, 0.04, (h, w))
    d65 = refl * (shade * folds * texture)[..., None]

    if light == "mixed":
        truth = C.white_of_xy(*LIGHTS["D65"])
        cast = d65 @ C.inv3(C.correction_matrix(truth)).T
        left = (xx < w / 2) & (labels == BACKGROUND)
        to_a = C.inv3(C.correction_matrix(C.white_of_xy(*LIGHTS["A"])))
        cast[left] = d65[left] @ to_a.T
    else:
        truth = C.white_of_xy(*LIGHTS[light])
        cast = d65 @ C.inv3(C.correction_matrix(truth)).T

    k = exposure / np.percentile(cast.max(axis=2), 99)
    cast, d65 = cast * k, d65 * k
    cast = np.clip(cast + rng.normal(0, 0.003, cast.shape), 0.0, 1.0)
    return Scene(C.to8(cast), labels, truth, d65, kind, light, seed, colours)
