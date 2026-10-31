"""Writes the fixtures the Kotlin engine must reproduce (D10, accuracy LLD §3).

    python spike/make_fixtures.py [OUT_DIR]

Regenerate after any change to the algorithms, on both sides, in the same commit.
"""
import os
import sys

from gcm import labels as L
from gcm.engine import analyse, linear
from gcm.estimate import neutral
from gcm.fixtures import write
from gcm.scenes import make

# name, seed, kind, light, extra, and a check that the scene shows what it is for.
SPECS = [
    ("outfit-tungsten", 101, "outfit", "A", {}, None),
    ("outfit-daylight", 102, "outfit", "D65", {}, None),
    ("outfit-fluorescent", 103, "outfit", "F2", {}, None),
    ("dominant-tungsten", 104, "dominant", "A", {}, None),
    ("dominant-overcast", 105, "dominant", "D75", {}, None),
    ("dominant-led", 106, "dominant", "LED3000", {}, None),
    ("neutrals-sun", 107, "neutrals", "D55", {}, None),
    ("neutrals-tungsten", 108, "neutrals", "A", {}, None),
    ("no-skin-fluorescent", 109, "no-skin", "F2", {}, lambda a: a.estimate.answers["SKIN"] is None),
    ("mixed-window-lamp", 110, "outfit", "mixed", {}, None),
    ("overexposed-daylight", 111, "neutrals", "D65", {"exposure": 1.6},
     lambda a: any(g.clipped for g in a.garments)),
    ("centre-box-tungsten", 112, "outfit", "A", {"centre": True},
     lambda a: [g.code for g in a.garments] == [L.SELECTED]),
    ("no-scene-tungsten", 113, "outfit", "A", {"wall_to_wall": True},
     lambda a: a.estimate.reason == "NO_SCENE" and a.method == "NONE"),
]

NEUTRAL_AT = (0.04, 0.05)


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    names = []
    for name, seed, kind, light, extra, check in SPECS:
        for attempt in range(50):
            s = seed + 1000 * attempt
            scene = make(s, kind, light, exposure=extra.get("exposure", 0.9),
                         wall_to_wall=extra.get("wall_to_wall", False))
            labels = L.centre(96, 72) if extra.get("centre") else scene.labels
            a = analyse(scene.img8, labels)
            # Keep the level well away from its line, so a last-digit difference
            # between Python and Kotlin cannot flip it.
            near_line = a.estimate.score > 0 and abs(a.estimate.score - 0.5) < 0.02
            if not near_line and (check is None or check(a)):
                break
        else:
            raise SystemExit(f"{name}: no seed in 50 gave a usable scene")
        result = neutral(linear(scene.img8), *NEUTRAL_AT)
        write(os.path.join(out_dir, name + ".txt"), name, scene.img8, labels, scene.truth, a,
              NEUTRAL_AT, result)
        est = a.estimate
        print(f"{name:24s} seed={s:<6d} level={est.level:4s} score={est.score:.3f} "
              f"reason={est.reason or '-':13s} garments={[L.NAMES[g.code] for g in a.garments]}")
        names.append(name)
    with open(os.path.join(out_dir, "index.txt"), "w", newline="\n") as f:
        f.write("\n".join(names) + "\n")
    print(f"wrote {len(names)} fixtures to {out_dir}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "color-engine/src/test/resources/fixtures")
