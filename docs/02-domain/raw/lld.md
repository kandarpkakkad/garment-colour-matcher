# RAW path — LLD

Code: `color-engine/.../raw/{Dng,CameraProfile,RawImages}.kt`. Tests:
`color-engine/src/test/.../raw/`. No Python twin (D23).

## 1. Reading the DNG — `Dng.read(file)`

TIFF, either byte order. Starting at IFD0, the reader walks IFD0 and every IFD named by
`SubIFDs` (330), and takes the first whose `NewSubFileType` is 0 and `PhotometricInterpretation`
is 32803 (CFA). Android's `DngCreator` writes the raw image as IFD0 itself.

That IFD must have, or the read fails with a `DngException` naming the value:

| Tag | Required |
|---|---|
| `Compression` (259) | 1, uncompressed |
| `BitsPerSample` (258) | 16 |
| `SamplesPerPixel` (277) | 1 |
| `StripOffsets` / `StripByteCounts` (273 / 279) | present (tiles are refused) |
| `CFARepeatPatternDim` (33421) | 2, 2 |
| `CFAPattern` (33422) | four values, each 0 (R), 1 (G) or 2 (B) |
| `CFAPlaneColor` (50710) | absent, or 0, 1, 2 |

Strips are read in order into one `ShortArray` (unsigned 16-bit, row-major), in the file's byte
order.

Metadata, looked up in the raw IFD first, then IFD0:

| Tag | Default when absent |
|---|---|
| `BlackLevelRepeatDim` (50713), `BlackLevel` (50714) | 0. One value, or one per CFA position |
| `WhiteLevel` (50717) | 65535 |
| `ActiveArea` (50829) — top, left, bottom, right | the whole image |
| `DefaultCropOrigin` / `DefaultCropSize` (50719 / 50720) | the active area. Relative to it |
| `Orientation` (274) | 1 |
| `ColorMatrix1/2` (50721/2), `CameraCalibration1/2` (50723/4), `ForwardMatrix1/2` (50964/5) | matrix 1 is required; the others optional; calibration = identity |
| `AnalogBalance` (50727) | (1, 1, 1) |
| `AsShotNeutral` (50728), `AsShotWhiteXY` (50729) | see §4 |
| `CalibrationIlluminant1/2` (50778/9) | unknown |
| `BaselineExposure` (50730) | 0 EV |
| `OpcodeList2` (51009) | none |

## 2. Linear values

For a sample `v` at raw `(x, y)`, CFA position `p = 2·(y mod 2) + (x mod 2)`:

```
lin = (v − black[p]) / (white − black[p])
saturated  ⇔ lin ≥ 0.98          (decided before the gain map)
lin = lin · gainMap(x, y)         (OpcodeList2, below)
lin = clamp(lin, 0, 1)
```

**`OpcodeList2`** is big-endian whatever the file's order: a count, then per opcode its id,
version, flags, parameter byte count and parameters. **GainMap** (id 9) is applied; every
other opcode is skipped. A GainMap's parameters: `Top, Left, Bottom, Right, Plane, Planes,
RowPitch, ColPitch, MapPointsV, MapPointsH` (uint32), `MapSpacingV, MapSpacingH, MapOriginV,
MapOriginH` (float64), `MapPlanes` (uint32), then `MapPointsV · MapPointsH · MapPlanes`
float32 gains, plane-fastest. It applies to pixels with `Top ≤ y < Bottom`, `(y − Top) mod
RowPitch = 0`, and likewise in x. The gain is bilinear in the map at relative position
`((y + 0.5) / H, (x + 0.5) / W)` of the raw image, `i = (rel − origin) / spacing`, clamped to
the map. `DngCreator` writes one map per Bayer position (pitch 2).

## 3. Demosaic

- **Full** (the default-crop area ≤ 25 MP): each colour at a pixel is its own sample if the CFA
  puts that colour there, else the mean of the in-image neighbours of that colour within ±1 in
  x and y (bilinear on a Bayer grid).
- **Half** (above 25 MP): one output pixel per 2 × 2 quad aligned to even raw coordinates:
  each colour is the mean of the quad's samples of that colour.

A demosaiced pixel is **saturated** if any raw sample it used was (§2).

## 4. The camera profile — `CameraProfile`

Calibration illuminants (EXIF LightSource code → K): 17 A 2856 · 18 B 4874 · 19 C 6774 ·
20 D55 5503 · 21 D65 6504 · 22 D75 7504 · 23 D50 5003 · 24 ISO tungsten 3200 · 1 daylight
5500 · 2 fluorescent 4150 · 3 tungsten 2850 · 4 flash 5500 · 9 fine weather 5500 · 10 cloudy
6500 · 11 shade 7500 · 12 daylight fluorescent 6430 · 13 day white fluorescent 5000 · 14 cool
white fluorescent 4150 · 15 white fluorescent 3450. Anything else: unknown.

**Interpolation** (DNG 1.4, §6). With two matrices and both illuminants known, at temperature
`t`: `g = clamp((1/t − 1/t₂) / (1/t₁ − 1/t₂), 0, 1)` and `X(t) = g·X₁ + (1 − g)·X₂` for each of
`ColorMatrix`, `CameraCalibration`, `ForwardMatrix`. Otherwise matrix 1 for every `t`.

```
XYZtoCamera(t) = AB · CC(t) · CM(t)
```

**Temperature of a camera neutral `n`**: from D50's xy, repeat up to 20 times —
`t = McCamy(xy)` clamped to the calibrated range; `XYZ = XYZtoCamera(t)⁻¹ · n`; `xy` of that —
until xy moves less than 1e-7.

**Camera → XYZ (D50) for neutral `n`**, at its temperature `t`:

- With `ForwardMatrix`: `ref = (AB·CC(t))⁻¹ · n`;
  `C = FM(t) · diag(1/ref) · (AB·CC(t))⁻¹`. By the standard, `FM · (1, 1, 1) = D50`, so
  `C · n = D50`. **Phones write `ForwardMatrix` rounded** — the OnePlus 6T's rows sum to
  (0.9609, 1, 0.8203), not D50's (0.9642, 1, 0.8252) — which tinted every neutral by 0.8 %.
  Each row is rescaled on reading so the sum is exactly D50, as the DNG SDK does. Found
  2026-11-01 by `RawTest` on the OnePlus matrices.
- Without: `W = XYZtoCamera(t)⁻¹ · n`; `C = Bradford(W → D50) · XYZtoCamera(t)⁻¹`, scaled so
  that `(C · n).Y = 1`.

D50 here is `(0.96422, 1, 0.82521)`.

**Camera → linear sRGB for neutral `n`**: `S(n) = M⁻¹ · Bradford(D50 → Ws) · C(n)`, with `Ws`
the sRGB matrix's own white (colour LLD §3). `S(n) · n = (1, 1, 1)`.

**The working transform** `T = k · S(n₆₅)`, where `n₆₅ = XYZtoCamera(6504 K) · D65`, scaled
to max 1: camera RGB as a camera white-balanced for daylight would render it. The scale
`k = 0.9 / maxᵣ Σ_c max(S_rc, 0)` keeps every unsaturated sensor value below 0.9 in every
channel. Without it, white-balancing pushed bright but unclipped pixels past 0.95, where the
estimator treats them as clipped, and a tungsten test scene had no usable pixels at all.
Clipping on RAW is decided at the sensor (§2), not in the working space.

**As-shot neutral**: `AsShotNeutral`; else `XYZtoCamera(t) · XYZ(AsShotWhiteXY)` at that xy's
temperature; else `n₆₅`. Its working-space light is `T · n`, normalised.

## 5. The correction — `RawCorrection(profile, gain)`

For a light `e` in the working space (an estimate, a tap, or the as-shot light):

```
n  = T⁻¹ · e, each component at least 1e-3 · max (a light outside the camera's gamut)
n  = n · (1 / luminance(T · n))      # so working luminance is kept
F  = gain · S(n)                      # camera RGB → corrected linear sRGB
W  = F · T⁻¹                          # the same, on working-space values
```

`W` is what the engine's `Correction.matrix(e)` returns (pipeline LLD §1), so garments are
measured exactly as on the JPEG path. Its `fallback` — the light used when there is no
estimate — is the as-shot light: "as the phone made it" (D7).

## 6. Exposure

`gain = 2^BaselineExposure · clamp(0.9 / p99, 0.5, 8)`, where `p99` is the 99th percentile of
the per-pixel maximum channel over the working image's unsaturated pixels. Stored on the
capture as `exposure_gain`.

## 7. The working image — `RawImages.working(dng, profile)`

1. The default crop, its origin rounded down and its size rounded down to even.
2. Half-size quads over it (§3), in camera RGB.
3. Box-averaged to the working size: at most 512 px on the long side (D14), the size computed
   from the *oriented* dimensions.
4. A block that contained a saturated quad becomes `(1, 1, 1)` — clipped, so it never votes
   (illuminant LLD §1).
5. Every other block: `T · rgb`.
6. Oriented (§8).

`RawImages.preview(working, W)` encodes `W · px` to 8-bit for the segmentation models; the
pipeline passes the as-shot `W`, so the models see a normally white-balanced picture.

## 8. Rendering — `RawImages.render(dng, F, rows)`

Output is the default crop, oriented. For each output pixel: map to raw coordinates (below),
demosaic (§3), and write `encode(F · rgb)` — or white if the pixel is saturated. Rows are
rendered in independent ranges so the app can run them in parallel.

Orientation, output `(ox, oy)` → stored crop `(sx, sy)` for a crop `w × h`:

| Tag | Output size | sx | sy |
|---|---|---|---|
| 1 | w × h | ox | oy |
| 2 | w × h | w−1−ox | oy |
| 3 | w × h | w−1−ox | h−1−oy |
| 4 | w × h | ox | h−1−oy |
| 5 | h × w | oy | ox |
| 6 | h × w | oy | h−1−ox |
| 7 | h × w | w−1−oy | h−1−ox |
| 8 | h × w | w−1−oy | ox |

## 9. Memory

The raw samples (2 bytes per pixel, 24 MB at 12 MP) and the output bitmap (4 bytes per
pixel). No float copy of the full image exists.
