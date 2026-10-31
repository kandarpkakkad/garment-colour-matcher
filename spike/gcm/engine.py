"""analyse() — the same entry point as Engine.analyse in Kotlin. Pipeline LLD §1."""
from dataclasses import dataclass

import numpy as np

from . import colour as C
from .estimate import Estimate, estimate, normalise
from .garments import measure

WHITE = normalise(np.ones(3))


@dataclass
class Analysis:
    estimate: Estimate
    applied: np.ndarray
    method: str
    matrix: np.ndarray
    garments: list


def linear(img8):
    return C.DECODE_LUT[img8]


def analyse(img8, labels, override=None):
    img = linear(img8)
    est = estimate(img, labels)
    if override is not None:
        applied, method = normalise(override), "NEUTRAL_TAP"
    elif est.combined is not None:
        applied, method = est.combined, "ESTIMATED"
    else:
        # No usable scene: the identity correction, the photo as the phone made it (D7).
        applied, method = WHITE, "NONE"
    matrix = C.correction_matrix(applied)
    return Analysis(est, applied, method, matrix, measure(img, labels, matrix))
