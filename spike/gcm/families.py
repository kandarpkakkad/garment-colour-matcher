"""Colour families: a proxy for "a person would call it a different colour".
Accuracy LLD §4."""
import math

from .colour import delta_e2000


def family(lab):
    L, a, b = (float(v) for v in lab)
    chroma = math.hypot(a, b)
    if chroma < 10:
        return "black" if L < 30 else ("white" if L > 80 else "grey")
    hue = math.degrees(math.atan2(b, a)) % 360
    sector = int(((hue + 15) % 360) // 30)
    band = "dark" if L < 40 else ("light" if L > 70 else "mid")
    return f"h{sector}-{band}"


def is_family_error(true_lab, lab):
    return family(true_lab) != family(lab) and delta_e2000(true_lab, lab) > 5
