# Accuracy — LLD

## 1. The spike

```
spike/
  gcm/colour.py      colour LLD
  gcm/labels.py      parsing LLD §1, §5
  gcm/estimate.py    illuminant LLD
  gcm/garments.py    garments LLD
  gcm/engine.py      analyse() — pipeline LLD §1
  gcm/scenes.py      synthetic scenes (§2)
  gcm/fixtures.py    the fixture format (§3)
  gcm/families.py    colour families (§4)
  make_fixtures.py   writes color-engine/src/test/resources/fixtures/
  bench.py           the benchmark (§5)
  test_spike.py      self-checks: Sharma ΔE2000 pairs, Bradford D50→D65, curve round trip
```

Python 3.12, numpy; OpenCV only for `bench.py --gt` (16-bit PNG). Runs in Docker:
`tool/spike_in_docker.sh test | fixtures | bench [args]`.

## 2. Synthetic scenes

`scenes.make(seed, kind, light, w, h)` returns the 8-bit image as captured, its label map, the
true illuminant, and the true D65 image (same shading, no cast).

- **Reflectances** are 8-bit sRGB colours under D65, decoded to linear: the 24 ColorChecker
  patches and five wall colours for the background (random rectangles), six skin tones, four
  hair colours, fourteen garment colours.
- **Shading**: a smooth gradient across the frame (0.65–1.0) times a per-region fold pattern
  on garments; **texture**: ±4 % multiplicative noise.
- **Kinds**: `outfit` (a figure with top and trousers or skirt), `dominant` (a saturated top or
  dress over 55–70 % of the frame), `neutrals` (black, white, navy, grey, cream garments),
  `no-skin` (a torso crop: garments and background only).
- **Lights**, as xy: tungsten A (0.4476, 0.4074), warm LED 3000 K (0.4369, 0.4041), shop
  fluorescent F2 (0.3721, 0.3753), sun D55 (0.3324, 0.3474), daylight D65 (0.3127, 0.3290),
  overcast D75 (0.2990, 0.3149), and `mixed`: the left half of the frame under A, the right
  under D65, the figure under D65.
- **Rendering**: the true light's correction matrix `C_L` (colour LLD §3) is inverted and applied
  to the D65 image; exposure scaled so the 99th-percentile channel is 0.9; Gaussian noise
  σ = 0.003; clipped; encoded to 8-bit. The true illuminant is `M⁻¹ · XYZ_L`, normalised.

## 3. Fixtures

`make_fixtures.py` writes about a dozen scenes chosen to cover every kind, several lights, and
the edge cases: no usable scene, an overexposed white garment, tier 3's centre box, no skin.
Scenes whose score lands within 0.02 of the 0.5 line are re-seeded, so a rounding difference
cannot flip the level.

Format — text, one key per line, then the pixels:

```
gcm-fixture 1
name dominant-red-tungsten
size 96 72
truth 0.712 0.553 0.433
expect.GRAY_WORLD r g b          # or "-" when it did not answer
expect.SHADES_OF_GRAY r g b
expect.GRAY_EDGE r g b
expect.SKIN r g b
expect.combined r g b
expect.disagreement d            # or "-"
expect.support s
expect.score q
expect.level HIGH
expect.reason -                  # or the reason
expect.matrix m00 m01 m02 m10 m11 m12 m20 m21 m22
expect.garment TOP 0.5421 0 52.13 48.20 30.11      # label fraction clipped L a b
pixels
RRGGBBLL RRGGBBLL …              # one line per row; LL = label code, hex
```

`FixtureParityTest` (Kotlin) reads every fixture, runs `Engine.analyse`, and requires:

| | Tolerance |
|---|---|
| Each estimator's answer, the combined estimate | ≤ 0.01° |
| Disagreement | ≤ 0.01° |
| Support | ≤ 1e-9 |
| Score | ≤ 1e-3; level and reason equal |
| Matrix entries | ≤ 1e-4 |
| Garments | same labels, same order, fraction ≤ 1e-9, same `clipped` |
| **Garment Lab** | **ΔE2000 ≤ 0.5 — the phase 2 gate** |

Regenerate after any change to the algorithms, on both sides, in the same commit (D10).

## 4. Colour families

A proxy for "would a person call it a different colour":

- Chroma `C = √(a² + b²)`. If `C < 10`: achromatic — `black` if L < 30, `white` if L > 80,
  else `grey`.
- Otherwise: hue sector `⌊((h + 15) mod 360) / 30⌋` (12 sectors) and lightness band — `dark`
  L < 40, `light` L > 70, else `mid`.

A **family error** is a different family *and* ΔE2000 > 5, so a colour sitting on a boundary
does not count every time it wobbles.

## 5. The benchmark

`bench.py` with no arguments generates 6 lights × 4 kinds × 10 seeds (+ `mixed`), runs the
engine, and reports:

- Angular error, median and p90, for: gray-world over **all** pixels (the naive baseline the
  plan compares against), gray-world on usable pixels, shades-of-gray, gray-edge, skin
  anchor, combined, and combined *without* the skin vote — overall, per light, per kind. The
  last row exists because the skin anchor's value is unsettled (accuracy HLD, results).
- Garment ΔE2000 median and p90 for the combined estimate and for the naive baseline, and the
  family-error rate of each.
- The same split by confidence level: are `HIGH` results better than `LOW` ones?

`bench.py --gt FILE.csv --images DIR [--black N --saturation N]` runs the estimators on a
public dataset: `FILE.csv` rows are `image,r,g,b` (the measured illuminant in camera RGB),
images are linear 16-bit PNGs; `--black` and `--saturation` are the dataset's levels. There are
no garment masks in such a set, so every pixel is scene. It measures the estimators, not the
garment mitigation. Check the dataset's README for its levels; the defaults are 0 and 65535.
