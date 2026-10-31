# Pipeline — LLD

Code: `app/src/main/kotlin/com/garmentcolour/app/pipeline/{Pipeline,Bitmaps,Records}.kt`,
and `color-engine/.../{Engine,LinearImage}.kt` (`Corrector` is in `Engine.kt`).

## 1. The engine's entry point

```kotlin
Engine.analyse(image: LinearImage, labels: LabelMap, override: Vec3? = null,
               correction: Correction = SrgbCorrection): Analysis

interface Correction {
  fun matrix(e: Vec3): Mat3   // working space → corrected linear sRGB, for a light e
  val fallback: Vec3          // the light used when there is no estimate
}
// SrgbCorrection: Bradford in linear sRGB (colour LLD §3); fallback white → the identity.
// RawCorrection:  the DNG chain (raw LLD §5); fallback the phone's as-shot light.

Analysis(
  estimate: Estimate,     // illuminant LLD §3–5, always computed
  applied:  Vec3,         // override ?: estimate.combined ?: correction.fallback
  method:   Method,       // NEUTRAL_TAP if override, else ESTIMATED, else NONE
  matrix:   Mat3,         // correction.matrix(applied)
  garments: List<Garment> // garments LLD, measured through `matrix`
)
```

The fixtures run with the default `SrgbCorrection`; RAW never changes what they check.

`Engine.VERSION` (now 1) is stored on every capture. Bump it when any output for the same
input would change.

## 2. A new capture

`Pipeline.process(input, progress)`, on `Dispatchers.Default`:

| Step | Progress | What |
|---|---|---|
| 1 | — | `id` = a random UUID. `files/captures/<id>/` created. The input is **moved** there as `original.jpg` (camera) or **copied** from the picker's URI (import) |
| 2 | `DECODING` | Decode (§4) to a mutable sRGB `ARGB_8888` bitmap `B`, `W × H` |
| 3 | `DECODING` | Working size: `s = 512 / max(W, H)` if that is < 1, else 1; `w = max(1, round(W·s))`, `h` likewise. `LinearImage` by box-averaging `B` in linear light (§5) |
| 4 | `FINDING_GARMENTS` | `small` = `B` scaled to `w × h` with filtering. MediaPipe and SegFormer each return a class map of `w × h` bytes, or nothing. `Fusion.fuse` → label map and tier; neither → `Tier3.centre` |
| 5 | `READING_LIGHT` | `Engine.analyse(image, labels)` |
| 6 | `CORRECTING` | `Corrector(matrix)` over `B` in strips of 64 rows, in place (§6) |
| 7 | `SAVING` | `corrected.jpg` (JPEG, quality 95, gain map removed) and `labels.bin` written, each to a `.tmp` then renamed |
| 8 | `SAVING` | One Room transaction: the capture row and its garment rows (storage schema) |

Any exception between 1 and 8 deletes the directory and rethrows; nothing is written to Room
before step 8.

**A RAW capture** (D23, raw LLD) differs in steps 1–7:

| Step | RAW |
|---|---|
| 1 | The DNG is moved to `original.dng` |
| 2 | `Dng.read`; `CameraProfile` from its tags |
| 3 | `RawImages.working` (raw LLD §7); the exposure gain (§6) |
| 4 | The segmenters get `RawImages.preview` with the as-shot light, at working size |
| 5 | `Engine.analyse(…, correction = RawCorrection(profile, gain))` |
| 6–7 | Two renders, each in parallel row ranges into one bitmap: `original.jpg` with the as-shot light (the *before*), `corrected.jpg` with the applied light |

The record carries `capture_path = RAW` and the gain. Re-renders read `original.dng` again and
render only `corrected.jpg`.

**Tier 3 is refined** (D24): when neither model ran, the centre box goes through
`GrabCut.segment`; `box(id, …)` does the same with the dragged box.

## 3. Re-rendering a saved capture

`Pipeline.retap(id, u, v)`, `Pipeline.useEstimate(id)`, `Pipeline.box(id, rect)` each:

1. Decode `original.jpg` (§4) and rebuild the working image (§5) — the same input gives the
   same working image.
2. Labels: `labels.bin`, or for `box` the new `GrabCut.segment` map (and tier `box`).
3. Override: `retap` samples the neutral (illuminant LLD §6; a refusal is returned to the
   screen, nothing changes); `useEstimate` and `box` pass none.
4. Steps 5–7 of §2, then one transaction: update the capture row, replace its garments, insert
   the adjustment (storage schema `adjustments`).

`corrected.jpg` is replaced by rename, so a screen showing it never reads half a file.

## 4. Decoding

`ImageDecoder` with: the software allocator, a mutable result, target colour space sRGB
(a Display P3 import is converted), EXIF orientation applied by the decoder. If
`width · height > 16 000 000`, a target size scaled by `√(16e6 / (W·H))` is set (D14) and the
capture records `downscaled = true` and the source's megapixels.

## 5. The working image

`BoxDownscaler(W, H, w, h)` takes `B`'s rows in strips: source pixel `(x, y)` adds its decoded
linear value to working pixel `(⌊x·w/W⌋, ⌊y·h/H⌋)`; the result is each sum divided by its
count. Averaging in linear light, not in encoded values, keeps the chromaticity of mixed
pixels right.

## 6. Correcting in place

`Corrector` holds the decode table (256) and the encode table (4096) (colour LLD §1) and `C` as
nine floats. For each pixel: decode three channels, multiply, encode with the table, keep
alpha. A 12 MP image is 190 strips of 64 rows; one strip buffer of `W · 64` ints is reused.

## 7. Memory

At most: `B` (`4 · W · H` bytes, 64 MB at 16 MP), one strip, the working image (2.4 MB) and
the class maps. `android:largeHeap` is set. The original is never held as a second bitmap;
the before/after view decodes it separately at screen size.

## 8. On start

`CaptureStore.sweep()`: delete `cache/pending/*`; delete any `files/captures/<id>/` with no
row; leave any row whose files are missing — the result screen says the image is missing,
and the row can be deleted.
