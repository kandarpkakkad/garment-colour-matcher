"""The label set and tier 3. docs/02-domain/parsing/lld.md §1, §5.

Codes are stored in labels.bin and in fixtures: never renumber.
"""
import numpy as np

BACKGROUND, HAIR, SKIN = 0, 1, 2
TOP, SKIRT, TROUSERS, DRESS, SCARF, CLOTHING, SELECTED = 10, 11, 12, 13, 14, 15, 16
ACCESSORY = 20

SCENE, SKIN_ROLE, GARMENT, EXCLUDED = 0, 1, 2, 3

NAMES = {
    BACKGROUND: "BACKGROUND", HAIR: "HAIR", SKIN: "SKIN",
    TOP: "TOP", SKIRT: "SKIRT", TROUSERS: "TROUSERS", DRESS: "DRESS", SCARF: "SCARF",
    CLOTHING: "CLOTHING", SELECTED: "SELECTED", ACCESSORY: "ACCESSORY",
}
CODES = {v: k for k, v in NAMES.items()}

GARMENT_CODES = (TOP, SKIRT, TROUSERS, DRESS, SCARF, CLOTHING, SELECTED)

# Any code not listed is treated as ACCESSORY: excluded and unreported.
ROLE = np.full(256, EXCLUDED, dtype=np.int8)
ROLE[BACKGROUND] = SCENE
ROLE[HAIR] = SCENE
ROLE[SKIN] = SKIN_ROLE
for _c in GARMENT_CODES:
    ROLE[_c] = GARMENT


def roles(labels):
    return ROLE[labels]


CENTRE = (0.30, 0.20, 0.70, 0.90)


def box(w, h, rect):
    """Pixels whose centre lies inside the box are SELECTED, the rest BACKGROUND."""
    l, t, r, b = rect
    cx = (np.arange(w) + 0.5) / w
    cy = (np.arange(h) + 0.5) / h
    inside = ((cy[:, None] >= t) & (cy[:, None] < b)) & ((cx[None, :] >= l) & (cx[None, :] < r))
    return np.where(inside, SELECTED, BACKGROUND).astype(np.uint8)


def centre(w, h):
    return box(w, h, CENTRE)
