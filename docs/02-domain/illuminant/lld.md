# Illuminant — LLD

Code: `color-engine/.../Illuminant.kt`. Reference: `spike/gcm/estimate.py`. Both implement
exactly this; the fixtures check they agree (D10).

Input: the working image (linear float planes `r, g, b`, `w × h`, `N = w · h`) and its label
map (parsing LLD §1). All estimates are returned as **unit vectors** (L2) in linear RGB.

## 1. Which pixels may vote

A pixel is **usable** when all of:

- its label's role is `scene` or `skin` — background, hair or skin (parsing LLD §1);
- it is not **clipped**: `max(r, g, b) < 0.95`;
- it is not **dark**: `max(r, g, b) ≥ 0.005`.

`n` = the number of usable pixels. If `n < max(500, 0.02 · N)` there is **no estimate**
(§5).

## 2. Region weights

Regions are the labels `BACKGROUND`, `HAIR`, `SKIN`. For each region `R` with `n_R` usable
pixels:

```
f_R = n_R / n
W_R = min(f_R, 0.5)
w_p = W_R / n_R          for every usable pixel p in R
```

The weights are not renormalised here; every estimator divides by `Σ w`. A scene that is all
background still works: its one region carries all the weight there is.

## 3. The estimators

**Gray-world** (reported only, D6):
`e_c = Σ w_p x_pc / Σ w_p`.

**Shades-of-gray**, `p = 6`:
`e_c = (Σ w_p x_pc⁶ / Σ w_p)^(1/6)`.

**Gray-edge**, first order, `p = 6`, `σ = 1`:

1. Blur each channel of the **whole** image with a Gaussian, `σ = 1`, radius 3: kernel
   `k_i = exp(−i²/2)` for `i = −3…3`, normalised to sum 1; horizontal pass then vertical;
   coordinates outside the image clamp to the edge.
2. Gradient by central differences on the blurred planes, edges clamped:
   `gx = (B[x+1, y] − B[x−1, y]) / 2`, `gy = (B[x, y+1] − B[x, y−1]) / 2`,
   `m_pc = √(gx² + gy²)`.
3. The blur and the difference reach 4 pixels, so a pixel near a garment would carry the
   garment's colour into its edge. Only pixels in the **usable mask eroded by 4** vote:
   a pixel stays if every usable-mask pixel within Chebyshev distance 4 is usable
   (neighbours outside the image are ignored). Separable: a horizontal then a vertical
   minimum.
4. `e_c = (Σ_{p ∈ E} w_p m_pc⁶ / Σ_{p ∈ E} w_p)^(1/6)`, with the region weights of §2.

No answer if `|E| < 200`, or if every `m` is below `1e-9`.

**Skin anchor**:

1. `S` = usable pixels labelled `SKIN`. No answer if `|S| < max(300, 0.005 · N)`.
2. `m_c` = the plain mean of `x_pc` over `S`.
3. The canonical skin chromaticity `k` is the mean of the chromaticities (each channel
   divided by the channel sum) of the two ColorChecker skin patches, decoded from 8-bit sRGB:
   dark skin `(115, 82, 68)` and light skin `(194, 150, 130)`.
4. `e_c = m_c / k_c`.

Every answer is normalised to unit length. An estimator with a zero or non-finite result does
not answer.

## 4. Combination

`A` = the answers among shades-of-gray, gray-edge and skin anchor. If `A` is empty there is no
estimate. Otherwise:

```
combined     = normalise(Σ_{a ∈ A} a)
disagreement = max angle between any two members of A    (0 when |A| = 1)
```

## 5. Confidence

```
support = n / N
agree   = clamp(1 − disagreement / 6°, 0, 1)
enough  = clamp(support / 0.15, 0, 1)
voices  = 1 if |A| ≥ 2, else 0.45
score   = agree · enough · voices
level   = HIGH if score ≥ 0.5, else LOW
```

So with plenty of scene, two estimators more than 3° apart make it `LOW`; with full agreement,
`LOW` once less than 7.5 % of the frame was usable. **One estimator alone is never `HIGH`**
(its score is at most 0.45): with nothing to disagree with, there is no evidence either way.
(0.45, not 0.5: at exactly 0.5 a lone estimator with full support would sit on the line.)

A `LOW` result carries the first **reason** that applies:

| Reason | When | Shown as |
|---|---|---|
| `NO_SCENE` | no estimate at all | *Not enough of the scene to read the light* |
| `LITTLE_SCENE` | `support < 0.075` | *Most of the photo is garment; little else to read the light from* |
| `DISAGREE` | `disagreement > 3°` | *The light could not be pinned down* |
| `ONE_ESTIMATOR` | `|A| < 2` | *Only one way of reading the light worked on this photo* |
| `DISAGREE` / `LITTLE_SCENE` | none of the above, yet `LOW` (moderate disagreement *and* moderate support) | Whichever factor is smaller: `DISAGREE` if `agree ≤ enough`, else `LITTLE_SCENE` |

With **no estimate**: the applied illuminant is `(1, 1, 1)` normalised — the identity
correction (colour LLD §3) — `score = 0`, `level = LOW`, `reason = NO_SCENE`.

## 6. The neutral tap

Input: the tap as a fraction `(u, v)` of the image's width and height.

1. Centre `(cx, cy) = (⌊u · w⌋, ⌊v · h⌋)`, clamped into the image.
2. Radius `r = max(2, round(0.01 · max(w, h)))`; the patch is the `(2r + 1)²` square, cut
   to the image.
3. Valid pixels: neither clipped nor dark (§1). Labels do not matter — the person chose it.
4. If fewer than half the patch is valid, the tap is **refused**: `TOO_BRIGHT` if more patch
   pixels were clipped than dark, else `TOO_DARK`.
5. `e_c` = the median of `x_c` over the valid pixels, per channel (the mean of the middle two
   for an even count). Normalised.

The result's confidence is `level = USER`, `score = 1`. The estimate it replaced is kept
(storage schema) so *Use the estimate* can go back.

## 7. Edge cases

- **A grey scene.** All estimators agree on white; `C` is near identity. Correct.
- **Hair only.** Hair is a coloured object; its region alone carries all the weight. The
  estimate is likely warm; gray-edge on hair texture usually disagrees, which drops the
  confidence. Accepted.
- **Skin anchor across skin tones.** Skin chromaticity varies with tone more than the plan's
  "narrow band" suggests: the two ColorChecker patches differ by about 3°. It is one voice of
  three for that reason.
