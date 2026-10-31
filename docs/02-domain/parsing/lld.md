# Parsing — LLD

Code: `color-engine/.../{Labels,Fusion}.kt`. Reference: `spike/gcm/labels.py`.

## 1. The label set

One byte per working-image pixel. Codes are stored in `labels.bin` and in fixtures, so
**never renumber**; add new codes.

| Code | Label | Role | Reported as |
|---|---|---|---|
| 0 | `BACKGROUND` | scene | |
| 1 | `HAIR` | scene | |
| 2 | `SKIN` | skin | |
| 10 | `TOP` | garment | Top |
| 11 | `SKIRT` | garment | Skirt |
| 12 | `TROUSERS` | garment | Trousers |
| 13 | `DRESS` | garment | Dress |
| 14 | `SCARF` | garment | Scarf |
| 15 | `CLOTHING` | garment | Clothing |
| 16 | `SELECTED` | garment | Selected area |
| 20 | `ACCESSORY` | excluded | |

Roles: **scene** and **skin** may inform the estimate (illuminant LLD §1); **garment** is
reported and excluded; **excluded** is neither. Any other code read from a file is treated as
`ACCESSORY`.

## 2. Raw classes

**MediaPipe Selfie Multiclass** (category mask): 0 background, 1 hair, 2 body skin,
3 face skin, 4 clothes, 5 others (accessories).

**SegFormer B2 clothes** (`id2label` in the model's `config.json`): 0 Background, 1 Hat,
2 Hair, 3 Sunglasses, 4 Upper-clothes, 5 Skirt, 6 Pants, 7 Dress, 8 Belt, 9 Left-shoe,
10 Right-shoe, 11 Face, 12 Left-leg, 13 Right-leg, 14 Left-arm, 15 Right-arm, 16 Bag,
17 Scarf.

| SegFormer | → | MediaPipe | → |
|---|---|---|---|
| 0 | `BACKGROUND` | 0 | `BACKGROUND` |
| 2 | `HAIR` | 1 | `HAIR` |
| 11–15 | `SKIN` | 2, 3 | `SKIN` |
| 4 | `TOP` | 4 | `CLOTHING` |
| 5 | `SKIRT` | 5 | `ACCESSORY` |
| 6 | `TROUSERS` | other | `ACCESSORY` |
| 7 | `DRESS` | | |
| 17 | `SCARF` | | |
| 1, 3, 8, 9, 10, 16, other | `ACCESSORY` | | |

## 3. Fusion (D13)

Per pixel, `s` = SegFormer's label (mapped), `m` = MediaPipe's (mapped); either may be absent.

```
both absent         → caller uses tier 3 (§5)
only m              → m
only s              → s
both:
  s is a garment or ACCESSORY            → s
  m is CLOTHING or ACCESSORY             → ACCESSORY     # worn per MediaPipe, unnamed by SegFormer
  otherwise                              → s
```

Tier: `both`, `clothes-only` or `people-only` by which maps were present.

## 4. SegFormer input and output

**Input.** The working bitmap is scaled to 512 × 512 with bilinear filtering (aspect not kept
— the model's own processor does the same), then per channel
`(v / 255 − mean) / std`, mean `(0.485, 0.456, 0.406)`, std `(0.229, 0.224, 0.225)`, laid out
NCHW as `float32[1, 3, 512, 512]`. `Fusion.segformerInput(argb, 512)` does the arithmetic
from the already-scaled pixels.

**Output.** `logits: float32[1, 18, 128, 128]`. For each working pixel `(x, y)` of `w × h`:

```
u = (x + 0.5) · 128 / w − 0.5,   v = (y + 0.5) · 128 / h − 0.5     # clamped to [0, 127]
```

each class's logit is sampled bilinearly at `(u, v)` and the class with the highest value is
taken (lowest index on a tie). Upsampling the logits before the argmax is what the model's
own example does; it gives smoother edges than upsampling the argmax.

## 5. Tier 3 (D20, D24)

- **Box:** a rectangle as fractions `(l, t, r, b)` of the image, normalised so `l < r` and
  `t < b`, and at least 2 % of each side. `Tier3.box` marks pixels whose centre lies inside
  `SELECTED` and the rest `BACKGROUND`; it is GrabCut's input and its fallback.
- **Centre:** the box `(0.30, 0.20, 0.70, 0.90)` — the middle of a portrait frame, where an
  outfit usually is.

Both are refined by GrabCut (§7) in the pipeline; the label map stored is GrabCut's.

## 7. GrabCut — `GrabCut.segment(image, box)` (D24)

Code: `color-engine/.../GrabCut.kt`, `MaxFlow.kt`. Kotlin only.

1. **Small image.** The working image box-averaged in linear light to at most 160 px on its
   long side, then encoded to 8-bit sRGB values (as doubles): GrabCut compares colours as
   they look, not as light. The box is mapped onto it by the centre-inside rule.
2. **Seeds.** Pixels outside the box are background, fixed. Pixels inside are unknown, and
   start as garment.
3. **Colour models.** Two Gaussian mixtures, five components each, full covariance plus
   `1.0 · I`. Initialised by k-means: centres at the pixels whose luminance sits at quantiles
   `(i + 0.5)/5`, ten Lloyd iterations. Deterministic: same input, same output.
4. **Smoothness.** For 8-neighbours `m, n`:
   `V = (50 / dist) · exp(−β ‖z_m − z_n‖²)`, `β = 1 / (2 · mean ‖z_m − z_n‖²)` over all
   neighbour pairs (0 if the image is flat).
5. **Iterate four times:** assign each pixel to the most likely component of its current
   model; re-learn both models (garment from current garment pixels, background from every
   background pixel); build the graph; cut.
6. **Graph.** One node per unknown pixel. Source → node: `−ln p_bg(z)`; node → sink:
   `−ln p_fg(z)` (likelihoods floored at 1e-300). Unknown–unknown neighbours: `V` both ways.
   An unknown pixel's fixed-background neighbours add their `V` to its sink capacity.
   Max-flow by Dinic's algorithm, iterative (no recursion: a path can be thousands of nodes);
   the nodes still reachable from the source are garment.
7. **Clean-up.** Keep the largest 4-connected garment region.
8. **Back to the working image.** A working pixel whose centre is in the box is `SELECTED` if
   its small-image pixel is garment; everything else is `BACKGROUND` — usable scene.
9. **Fallback.** Fewer than 5 % of the box's working pixels selected, or the box smaller than
   4 × 4 small pixels: the plain `Tier3.box`, and the result says so (`refined = false`).

## 6. `labels.bin`

```
bytes 0–3   "GCL1"
bytes 4–7   width,  big-endian int32
bytes 8–11  height, big-endian int32
then        width × height label codes, row-major
```

A file with the wrong magic or length fails to read; the result screen then offers the box.
