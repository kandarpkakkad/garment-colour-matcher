# RAW path — HLD

Built 2026-11-01 (D23). Its gate — a measured ΔE2000 gain over the JPEG path on identical
scenes — has **not** been run: it needs a phone. Until it passes, RAW is opt-in.

## Why

A JPEG has already been white-balanced and tone-mapped by the phone. RAW is the sensor before
either, which removes two error sources at once: the phone's own white-balance guess, and its
unknown tone curve (the JPEG path decodes with the sRGB curve, which is not the phone's). The
camera's own colour characterisation travels inside the DNG, so camera RGB is turned into XYZ
with the matrices measured for that sensor, never treated as sRGB.

## Owns

- Reading the DNGs Android's `DngCreator` writes: Bayer data, black and white levels, the lens
  shading gain map, the colour matrices and the as-shot white point.
- Linearising, demosaicing, cropping and orienting.
- The camera profile: turning camera RGB into XYZ for any light, by the DNG standard.
- The **working space** the estimator reads, and the **correction** for an estimated light.
- Rendering the corrected image and the as-shot *before* image.

## Shape

```
DNG ──► Dng.read ──► RawImages.working ──► working image (camera RGB through T, D65 assumed)
              │                                   │
              │                       the same estimate, tap, garments as JPEG (D23)
              │                                   ▼
              └──► RawImages.render ◄── RawCorrection(e): F = gain · toSrgb(neutral of e)
                        │
                        ▼
                 corrected.jpg, and original.jpg rendered with the phone's AsShotNeutral
```

## Key decisions

- **One estimator for both paths.** RAW changes what the working image is made from, not what
  is done with it (D23).
- **The DNG colour chain, not Bradford in sRGB**, for RAW's correction: the camera's
  `ForwardMatrix` interpolated at the light's temperature is better than any fixed matrix.
- **Saturated sensor pixels render white** (D23).
- **No tone curve**; one exposure gain, recorded on the capture.
- **Full resolution up to 25 MP**, half-resolution above (D14).
- **No Python twin** (D23): tested against the DNG specification's own properties, synthetic
  DNGs, and real phone DNGs from raw.pixls.us (CC0) — see *Checked* below.

## Checked

On 2026-11-01, without a phone, `tool/test_in_docker.sh dng` ran three real DNGs from
raw.pixls.us (CC0) through the whole path — decode, working image, estimate (every pixel
treated as scene: there are no models on the JVM), render:

| File | Result |
|---|---|
| OnePlus 6T, `DngCreator` | Decoded and rendered, 4640 × 3480. No gain map in this file: corners vignette. Estimate `HIGH`, 2.5° from the phone's as-shot white |
| Xiaomi Poco X3, `DngCreator` | Decoded and rendered, 3984 × 2984, four lens-shading gain maps applied. Estimate `HIGH`, 2.2° from as-shot |
| Samsung S22, Samsung's own writer | Refused: its main image is a JPEG-XL-compressed linear DNG (PhotometricInterpretation 34892). CameraX never writes this |

Both renders were looked at: clouds and an overcast sky read neutral, brick and grass look
natural. Single-threaded on the build machine, a 12–16 MP render took 0.8–1.4 s; the app
renders row ranges in parallel. **None of this is a capture from this app on a phone.**

## Failure modes

| Failure | Outcome |
|---|---|
| The camera cannot capture RAW | The switch is shown disabled: *This camera does not offer RAW* |
| A DNG this reader does not support (compressed, linear, tiled, not 16-bit, not 2 × 2 Bayer) | *Could not read this RAW photo: <what is unsupported>*; nothing saved |
| No `AsShotNeutral` | The as-shot white comes from `AsShotWhiteXY`, else D65 |
| One colour matrix only, or an unknown calibration illuminant | That matrix is used for every light, uninterpolated |
| No lens-shading map in the DNG | Rendered without it: corners darker, possibly tinted |

## Non-goals

DNG writing; compressed, linear or tiled DNGs; X-Trans or four-colour sensors; noise
reduction; sharpening; distortion correction (`OpcodeList3`); tone curves; importing DNGs from
the picker on the RAW path (the picker sends them to Android's own decoder, the JPEG path).
