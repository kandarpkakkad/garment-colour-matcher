"""Self-checks for the reference. Run: python spike/test_spike.py"""
import csv
import os
import tempfile

import numpy as np

from gcm import colour as C
from gcm import labels as L
from gcm.engine import analyse, linear
from gcm.estimate import erode, neutral
from gcm.scenes import make

checks = []


def check(fn):
    checks.append(fn)
    return fn


# Sharma, Wu and Dalal (2005), table 1.
SHARMA = [
    (1, (50.0000, 2.6772, -79.7751), (50.0000, 0.0000, -82.7485), 2.0425),
    (2, (50.0000, 3.1571, -77.2803), (50.0000, 0.0000, -82.7485), 2.8615),
    (3, (50.0000, 2.8361, -74.0200), (50.0000, 0.0000, -82.7485), 3.4412),
    (7, (50.0000, 0.0000, 0.0000), (50.0000, -1.0000, 2.0000), 2.3669),
    (17, (50.0000, 2.5000, 0.0000), (73.0000, 25.0000, -18.0000), 27.1492),
    (18, (50.0000, 2.5000, 0.0000), (61.0000, -5.0000, 29.0000), 22.8977),
    (19, (50.0000, 2.5000, 0.0000), (56.0000, -27.0000, -3.0000), 31.9030),
    (20, (50.0000, 2.5000, 0.0000), (58.0000, 24.0000, 15.0000), 19.4535),
    (21, (50.0000, 2.5000, 0.0000), (50.0000, 3.1736, 0.5854), 1.0000),
    (25, (60.2574, -34.0099, 36.2677), (60.4626, -34.1751, 39.4387), 1.2644),
    (26, (63.0109, -31.0961, -5.8663), (62.8187, -29.7946, -4.0864), 1.2630),
    (34, (2.0776, 0.0795, -1.1350), (0.9033, -0.0636, -0.5514), 0.9082),
]


@check
def delta_e2000_matches_sharma():
    for n, a, b, want in SHARMA:
        got = C.delta_e2000(a, b)
        assert abs(got - want) < 1e-4, f"pair {n}: {got} != {want}"
        assert abs(C.delta_e2000(b, a) - want) < 1e-4, f"pair {n} reversed"


@check
def bradford_d50_to_d65_matches_lindbloom():
    want = np.array([[0.9555766, -0.0230393, 0.0631636],
                     [-0.0282895, 1.0099416, 0.0210077],
                     [0.0122982, -0.0204830, 1.3299098]])
    got = C.cat(np.array([0.96422, 1.0, 0.82521]), C.D65)
    assert np.abs(got - want).max() < 1e-5, got


@check
def curve_round_trips_every_8bit_value():
    v = np.arange(256)
    assert (C.to8(C.DECODE_LUT[v]) == v).all()


@check
def correction_makes_the_light_neutral_and_keeps_luminance():
    e = C.white_of_xy(0.4476, 0.4074)
    m = C.correction_matrix(e)
    out = m @ (e * 0.3)
    assert np.abs(out / out.mean() - 1).max() < 1e-9, out
    assert abs(C.luminance(out) - C.luminance(e * 0.3)) < 1e-9


@check
def white_light_is_the_identity():
    m = C.correction_matrix(np.ones(3))
    assert np.abs(m - np.eye(3)).max() < 1e-12, m


@check
def lab_of_white_is_100():
    lab = C.lab_of_linear(np.ones(3))
    assert abs(lab[0] - 100) < 1e-3 and abs(lab[1]) < 0.01 and abs(lab[2]) < 0.01, lab


@check
def erosion_ignores_the_outside():
    m = np.ones((20, 20), dtype=bool)
    assert erode(m, 4).all()
    m[10, 10] = False
    e = erode(m, 4)
    assert not e[6:15, 6:15].any() and e[5, 5] and e[15, 15]


@check
def removing_garments_beats_gray_world_on_a_dominant_garment():
    s = make(4, "dominant", "D65")
    a = analyse(s.img8, s.labels)
    img = linear(s.img8)
    naive = img.reshape(-1, 3).mean(axis=0)
    assert C.angle(a.estimate.combined, s.truth) < C.angle(naive, s.truth), "mitigation lost"


@check
def a_neutral_tap_on_grey_finds_the_light():
    e = C.white_of_xy(0.4476, 0.4074)
    img = np.ones((40, 40, 3)) * (e / e.max() * 0.5)
    kind, got = neutral(img, 0.5, 0.5)
    assert kind == "ok" and C.angle(got, e) < 1e-6


@check
def a_neutral_tap_on_black_is_refused():
    img = np.zeros((40, 40, 3))
    assert neutral(img, 0.5, 0.5) == ("refused", "TOO_DARK")
    img[:] = 1.0
    assert neutral(img, 0.5, 0.5) == ("refused", "TOO_BRIGHT")


@check
def nothing_to_read_means_no_correction():
    s = make(113, "outfit", "A", wall_to_wall=True)
    a = analyse(s.img8, s.labels)
    assert a.estimate.reason == "NO_SCENE" and a.method == "NONE"
    assert np.abs(a.matrix - np.eye(3)).max() < 1e-6


@check
def centre_box_selects_the_middle():
    lab = L.centre(10, 10)
    assert lab[5, 5] == L.SELECTED and lab[0, 0] == L.BACKGROUND


@check
def dataset_runner_reads_16bit_pngs():
    import cv2
    import bench

    with tempfile.TemporaryDirectory() as d:
        rows = []
        for i, light in enumerate(["A", "D65", "F2"]):
            s = make(200 + i, "outfit", light, 64, 48)
            raw = np.round(linear(s.img8) * 60000 + 500).astype(np.uint16)
            cv2.imwrite(os.path.join(d, f"img{i}.png"), raw[..., ::-1])
            rows.append([f"img{i}", *s.truth])
        with open(os.path.join(d, "gt.csv"), "w", newline="") as f:
            csv.writer(f).writerows([["image", "r", "g", "b"]] + rows)
        errs = bench.dataset(os.path.join(d, "gt.csv"), d, black=500, saturation=60500)
        assert len(errs["COMBINED"]) == 3
        assert all(e is not None and e < 45 for e in errs["SHADES_OF_GRAY"])


if __name__ == "__main__":
    for fn in checks:
        fn()
        print(f"ok  {fn.__name__}")
    print(f"{len(checks)} checks passed")
