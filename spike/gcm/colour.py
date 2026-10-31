"""The settled colour maths. docs/02-domain/colour/lld.md.

The Kotlin engine (Srgb.kt, Colour.kt, Bradford.kt) implements exactly this; the
fixtures check they agree (D10). Rounding is floor(x + 0.5) on both sides —
Python's round() is banker's rounding and Kotlin's is not.
"""
import math

import numpy as np


def inv3(m):
    """Cofactor inverse, the same formula Mat3.inverse() uses."""
    a, b, c = m[0]
    d, e, f = m[1]
    g, h, i = m[2]
    A = e * i - f * h
    B = -(d * i - f * g)
    Cc = d * h - e * g
    det = a * A + b * B + c * Cc
    adj = np.array([
        [A, -(b * i - c * h), b * f - c * e],
        [B, a * i - c * g, -(a * f - c * d)],
        [Cc, -(a * h - b * g), a * e - b * d],
    ])
    return adj / det


M = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
])
M_INV = inv3(M)
D65 = np.array([0.95047, 1.0, 1.08883])
# The white the sRGB matrix itself defines. It differs from the published D65
# constant in the seventh digit; adapting to it keeps a neutral exactly neutral
# and makes a white light exactly the identity. Lab still uses D65.
SRGB_WHITE = M @ np.ones(3)

BRADFORD = np.array([
    [0.8951, 0.2664, -0.1614],
    [-0.7502, 1.7135, 0.0367],
    [0.0389, -0.0685, 1.0296],
])
BRADFORD_INV = inv3(BRADFORD)


def decode(c):
    c = np.asarray(c, dtype=np.float64)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def encode(lin):
    lin = np.clip(np.asarray(lin, dtype=np.float64), 0.0, 1.0)
    return np.where(lin <= 0.0031308, 12.92 * lin, 1.055 * lin ** (1 / 2.4) - 0.055)


DECODE_LUT = decode(np.arange(256) / 255.0)


def to8(lin):
    return np.floor(255.0 * encode(lin) + 0.5).astype(np.uint8)


def luminance(rgb):
    rgb = np.asarray(rgb, dtype=np.float64)
    return rgb[..., 0] * M[1, 0] + rgb[..., 1] * M[1, 1] + rgb[..., 2] * M[1, 2]


def xyz_to_lab(xyz, white=D65):
    d = 6.0 / 29.0

    def f(t):
        return t ** (1.0 / 3.0) if t > d ** 3 else t / (3 * d * d) + 4.0 / 29.0

    fx, fy, fz = f(xyz[0] / white[0]), f(xyz[1] / white[1]), f(xyz[2] / white[2])
    return np.array([116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)])


def lab_of_linear(rgb):
    return xyz_to_lab(M @ np.asarray(rgb, dtype=np.float64))


def xy(xyz):
    s = xyz[0] + xyz[1] + xyz[2]
    return np.array([xyz[0] / s, xyz[1] / s])


def cct(x, y):
    n = (x - 0.3320) / (0.1858 - y)
    return 449 * n ** 3 + 3525 * n ** 2 + 6823.3 * n + 5520.33


def hex_of(rgb):
    v = to8(np.asarray(rgb, dtype=np.float64))
    return "#%02X%02X%02X" % (int(v[0]), int(v[1]), int(v[2]))


def cat(ws, wd):
    s = BRADFORD @ ws
    d = BRADFORD @ wd
    return BRADFORD_INV @ np.diag(d / s) @ BRADFORD


def correction_matrix(e):
    """C = M⁻¹ · CAT(scene white → sRGB's D65) · M  (D4)."""
    xs = M @ np.asarray(e, dtype=np.float64)
    xs = xs * (SRGB_WHITE[1] / xs[1])   # same luminance as the target white
    return M_INV @ (cat(xs, SRGB_WHITE) @ M)


def white_of_xy(x, y):
    """The linear-sRGB colour of a light with chromaticity (x, y), unit length."""
    xyz = np.array([x / y, 1.0, (1 - x - y) / y])
    e = M_INV @ xyz
    return e / np.linalg.norm(e)


def angle(a, b):
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    c = float(a @ b) / (math.sqrt(float(a @ a)) * math.sqrt(float(b @ b)))
    return math.degrees(math.acos(min(1.0, max(-1.0, c))))


def delta_e2000(lab1, lab2):
    """Sharma, Wu and Dalal (2005), kL = kC = kH = 1."""
    L1, a1, b1 = (float(v) for v in lab1)
    L2, a2, b2 = (float(v) for v in lab2)
    c1 = math.hypot(a1, b1)
    c2 = math.hypot(a2, b2)
    cb = (c1 + c2) / 2
    g = 0.5 * (1 - math.sqrt(cb ** 7 / (cb ** 7 + 25.0 ** 7)))
    a1p = (1 + g) * a1
    a2p = (1 + g) * a2
    c1p = math.hypot(a1p, b1)
    c2p = math.hypot(a2p, b2)
    h1p = 0.0 if (a1p == 0 and b1 == 0) else math.degrees(math.atan2(b1, a1p)) % 360
    h2p = 0.0 if (a2p == 0 and b2 == 0) else math.degrees(math.atan2(b2, a2p)) % 360

    dlp = L2 - L1
    dcp = c2p - c1p
    if c1p * c2p == 0:
        dhp = 0.0
    else:
        dhp = h2p - h1p
        if dhp > 180:
            dhp -= 360
        elif dhp < -180:
            dhp += 360
    dHp = 2 * math.sqrt(c1p * c2p) * math.sin(math.radians(dhp / 2))

    lbp = (L1 + L2) / 2
    cbp = (c1p + c2p) / 2
    if c1p * c2p == 0:
        hbp = h1p + h2p
    elif abs(h1p - h2p) <= 180:
        hbp = (h1p + h2p) / 2
    elif h1p + h2p < 360:
        hbp = (h1p + h2p + 360) / 2
    else:
        hbp = (h1p + h2p - 360) / 2

    t = (1 - 0.17 * math.cos(math.radians(hbp - 30))
         + 0.24 * math.cos(math.radians(2 * hbp))
         + 0.32 * math.cos(math.radians(3 * hbp + 6))
         - 0.20 * math.cos(math.radians(4 * hbp - 63)))
    dtheta = 30 * math.exp(-(((hbp - 275) / 25) ** 2))
    rc = 2 * math.sqrt(cbp ** 7 / (cbp ** 7 + 25.0 ** 7))
    sl = 1 + 0.015 * (lbp - 50) ** 2 / math.sqrt(20 + (lbp - 50) ** 2)
    sc = 1 + 0.045 * cbp
    sh = 1 + 0.015 * cbp * t
    rt = -math.sin(math.radians(2 * dtheta)) * rc
    return math.sqrt((dlp / sl) ** 2 + (dcp / sc) ** 2 + (dHp / sh) ** 2
                     + rt * (dcp / sc) * (dHp / sh))
