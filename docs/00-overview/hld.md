# Garment Colour — high-level design

## 1. Shape

Two Gradle modules and a Python reference.

```
┌──────────────────────────────── :app (Android, Kotlin, Compose) ──────────────────────────────┐
│                                                                                               │
│  ui/            capture · result · history · settings          (03-frontend)                 │
│    │                                                                                          │
│  pipeline/      decode ─► downscale ─► segment ─► analyse ─► correct ─► save                  │
│    │               │                     │           │          │         │                   │
│  camera ─ CameraX  │        models/ ─ MediaPipe Tasks│          │      data/ ─ Room + files    │
│  (01-platform)     │                ─ ONNX Runtime   │          │      (01-platform/storage)   │
│                    └──────────── bitmaps ⇄ engine types at this line only ───┘                │
└───────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                            │ depends on
┌───────────────────────── :color-engine (pure Kotlin/JVM, no Android) ─────────────────────────┐
│  colour      sRGB curve, XYZ, Lab, Bradford, ΔE2000                     (02-domain/colour)    │
│  illuminant  exclusion, weighting, 3 estimators, confidence, neutral tap (02-domain/illuminant)│
│  parsing     label set, fusion of two models, tier 3, SegFormer pre/post (02-domain/parsing)  │
│  garments    per-garment measurement                                     (02-domain/garments) │
│  Engine      analyse() = estimate + matrix + garments; Corrector         (02-domain/pipeline) │
└───────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                            │ same algorithms, checked by shared fixtures (D10)
┌──────────────────────────── spike/ (Python + numpy, phase 0) ─────────────────────────────────┐
│  the reference implementation · synthetic scenes · benchmark runner · fixture writer         │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. Who owns what

| Thing | Owner | Nobody else |
|---|---|---|
| Every number about colour | `:color-engine` | The app never does colour maths; it moves pixels in and out |
| Which pixels are garments | `parsing` in the engine, fed by `models/` in the app | The models return raw class maps; the engine decides what they mean (D13) |
| The light | `illuminant` | |
| Images on disk, rows in the database | `data/CaptureStore` | Screens read through it; only it deletes |
| Model files | `tool/models_in_docker.sh` | Nothing is committed (D12) |
| Expected values for the engine | `spike/` | Kotlin tests read the fixtures it writes, never their own copies |

## 3. What happens on a capture

1. **Capture.** CameraX writes a JPEG to the app's cache, or the picker's file is copied there.
2. **Decode.** The full image, orientation applied, as sRGB, capped at 16 MP (D14).
3. **Downscale.** Box-averaged in linear light to the working image, ≤ 512 px long side.
4. **Segment.** MediaPipe and SegFormer each produce a class map at working size; either may
   be missing. The engine fuses them into one label map (D13), or falls back to tier 3: the centre box, cut out by GrabCut (D20, D24).
5. **Analyse.** The engine estimates the light from non-garment pixels (D5, D6), scores its
   confidence (D7), builds the correction matrix (D4), and measures each garment.
6. **Correct.** The matrix is applied to the full image in strips of rows, in place.
7. **Save.** `original.jpg`, `corrected.jpg` and `labels.bin` go into the capture's
   directory; the capture, its garments and its estimate go into Room in one transaction.

A neutral tap, a dragged box, or *Use the estimate* re-runs 2, 3, 5, 6 and 7 for that capture,
with the stored label map instead of segmenting again.

## 4. Failure modes

| Failure | What the person sees | Why it is safe |
|---|---|---|
| A model file missing or failing to load | Settings shows which and why; captures use the other model, or tier 3 | Segmentation only restricts the estimate; tier 3 is always available (D20) |
| Both models fail on a photo | The centre of the frame is treated as the garment; a box can be dragged | |
| No usable scene left once garments are removed | Photo left as the phone made it; *Not enough of the scene to read the light* | No guess from nothing (D7) |
| Estimators disagree | Corrected, with *Low confidence* and the neutral tap offered | D7 |
| Tap on a blown-out or black spot | Refused, with the reason | Illuminant LLD §6 |
| Out of memory on a large import | Capped at 16 MP before decode (D14) | Pipeline LLD §4 |
| Camera permission refused | *Open a photo* still works | Never gate a feature on a check |
| App killed mid-capture | The pending file is swept on next start; no row was written | The row is written last, after the files |

## 5. Deliberately not here

A server, an account, the `INTERNET` permission, analytics or crash reporting (D3, D16); a
learned illuminant model (D6); colour names (D18); per-region light (D9).
