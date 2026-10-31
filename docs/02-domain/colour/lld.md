# Colour — LLD

Code: `color-engine/src/main/kotlin/com/garmentcolour/engine/{Srgb,Mat3,Colour,Bradford}.kt`.
Reference: `spike/gcm/colour.py`. Both must agree (D10).

## 1. The sRGB curve (IEC 61966-2-1)

```
decode(c) = c / 12.92                      if c ≤ 0.04045
          = ((c + 0.055) / 1.055) ^ 2.4    otherwise
encode(l) = 12.92 · l                      if l ≤ 0.0031308
          = 1.055 · l ^ (1/2.4) − 0.055    otherwise
```

`encode` clamps `l` to [0, 1] first. An 8-bit value is `round(255 · encode(l))`.

**Lookup tables**, used on every pixel of a full image:

- Decode: 256 entries, `decode(i / 255)`, as `Float`.
- Encode: 4096 entries over linear [0, 1], entry `i` = `round(255 · encode(i / 4095))`; a
  linear value `l` maps to entry `round(4095 · clamp(l))`. Off by at most one 8-bit level from
  the exact curve — the curve's steepest slope (12.92) over one table step is 0.8 levels.
  Tested.

## 2. Spaces

sRGB (D65) to XYZ, row-major:

```
M = | 0.4124564  0.3575761  0.1804375 |
    | 0.2126729  0.7151522  0.0721750 |
    | 0.0193339  0.1191920  0.9503041 |
```

`M⁻¹` is computed by the cofactor inverse, not typed in, on both sides.

- D65 white: `W = (0.95047, 1.0, 1.08883)`. Note `M · (1, 1, 1) ≈ W`.
- Luminance: `Y = 0.2126729 r + 0.7151522 g + 0.0721750 b`.
- xy: `x = X / (X + Y + Z)`, `y = Y / (X + Y + Z)`.
- Lab, white `W`: with `δ = 6/29`,
  `f(t) = t^(1/3)` if `t > δ³`, else `t / (3δ²) + 4/29`;
  `L = 116 f(Y/Wy) − 16`, `a = 500 (f(X/Wx) − f(Y/Wy))`, `b = 200 (f(Y/Wy) − f(Z/Wz))`.
- CCT, for display only (McCamy): `n = (x − 0.3320) / (0.1858 − y)`,
  `CCT = 449 n³ + 3525 n² + 6823.3 n + 5520.33`. Shown as "≈ 3100 K", and only when
  1500 K ≤ CCT ≤ 25000 K.
- Hex: `#RRGGBB` of the 8-bit encoding of the linear colour clamped to [0, 1].

## 3. Bradford and the correction matrix

```
B = |  0.8951   0.2664  −0.1614 |
    | −0.7502   1.7135   0.0367 |
    |  0.0389  −0.0685   1.0296 |

CAT(Ws → Wd) = B⁻¹ · diag(ρd/ρs, γd/γs, βd/βs) · B,   (ρ, γ, β) = B · W
```

The **correction matrix** for an illuminant `e` (linear sRGB, any positive length):

```
Ws = M · (1, 1, 1)                   # D65 as the sRGB matrix itself defines it
Xs = M · e;  Xs = Xs · (Ws.Y / Xs.Y) # the scene white, at Ws's luminance
C  = M⁻¹ · CAT(Xs → Ws) · M
```

The target is `Ws`, not the published constant `W`: the two differ in the seventh digit
(`Ws.Y = 1.0000001`), and adapting to `W` left neutrals off-neutral by 3e-7 and white light
not quite the identity. Found by the spike's own test, 2026-11-01. Lab still uses `W`.

Properties, all tested on both sides:

- `C · e ∝ (1, 1, 1)`, to 1e-12: what the light made of a neutral becomes neutral again.
- `C` preserves the luminance of neutrals, to 1e-12.
- `e = (1, 1, 1)` gives the identity, to 1e-12.

Applying it, per pixel: `p' = C · p`, then encode (which clamps).

## 4. Differences

**Angular error**, degrees: `acos(clamp(a·b / (|a| |b|), −1, 1)) · 180/π`.

**ΔE2000**, Sharma, Wu and Dalal (2005), with `kL = kC = kH = 1`. The edge cases that paper
calls out are handled as it says: the mean hue when the hues differ by more than 180°, and a
hue of 0 when chroma is 0. Tested against the paper's pairs 1, 2, 3, 7, 17, 18–21, 25, 26
and 34.

## 5. Numbers

The engine keeps image planes in `Float` (a 512 × 384 working image is 2.4 MB) and does every
sum, matrix and colour conversion in `Double`. The spike uses float64 throughout. The
difference this makes is far below the parity tolerances (accuracy LLD §3).
