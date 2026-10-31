# Garment Colour — product requirements

Status: v1, written 2026-11-01 from the build plan *Garment colour matcher — Android build plan*
(31 Oct 2026). Where this document and the plan differ, this document says why; the decisions
it cites are in [`00-overview/decisions.md`](00-overview/decisions.md).

## 1. The problem

A phone photo of clothes is a photo of the light as much as of the clothes. The phone's own
white balance guesses at the light, and indoors it guesses badly: a navy jumper under a
tungsten lamp comes out black, a cream shirt comes out white, burgundy reads as brown. You
cannot trust the colour of a garment in a photo you took, which is exactly when you wanted
to.

## 2. What v1 does

Point the phone at an outfit and get **the same photo back with the garments' true colours**:
folds, shadows and weave kept, the colour cast of the light removed. Each garment in the
photo is found and reported with its own measured colour.

Everything runs on the phone, from a single photo, **with no reference card in frame**.

## 3. Goals

| | Goal | How it is measured |
|---|---|---|
| G1 | The colour of a garment on a *confident* capture is right | ΔE2000 between the corrected garment and its true colour under D65 ([accuracy HLD](04-accuracy/hld.md)). The target is set from the phase 1 baseline, not guessed now |
| G2 | Texture survives | The correction is one linear map applied to every pixel (D4). The output has the input's resolution, up to 16 MP (D14) |
| G3 | Works offline from the first launch | No `INTERNET` permission in any build; both models ship inside the APK (D3) |
| G4 | Says when it is unsure | Every capture carries a confidence. A low one is shown, with a way out: tap something white or grey (D7, D8) |

## 4. Non-goals for v1

- **No palette, no matching, no colour names.** The output is measured colour — Lab, XYZ,
  linear RGB, hex — not "navy" (D18).
- **No accessories.** Hats, sunglasses, belts, shoes and bags are kept out of the light
  estimate but not reported (D13).
- **No reference card.** Tapping something white or grey that is already in the photo is
  allowed; carrying a card is not (D8).
- **One light per photo.** A garment in shadow next to one in window light gets one
  correction. This is a known limitation, not a bug (D9).
- No accounts, no sync, no backup, no upload, no analytics. Export is a JSON file the person
  asks for (D16).
- No live correction in the viewfinder, no video, no editing tools.

## 5. Who it is for

One person, installing a debug or release APK from GitHub onto an Android 16 phone. A Play
listing is phase 7 and out of scope for this build.

## 6. Concepts

| Term | Meaning |
|---|---|
| Capture | One photo and everything worked out from it: the corrected image, the light, the garments |
| Illuminant | The colour of the light falling on the scene, as the camera saw it |
| Correction | The one 3×3 matrix that maps the scene's light to daylight (D65), applied to every pixel |
| Garment | A region the parser labels top, skirt, trousers, dress or scarf (or "clothing" when only one model ran) |
| Confidence | How far the light estimate can be trusted: high, low, or set by the person |
| Neutral tap | The person taps something white or grey in the photo, and that spot becomes the light |
| Tier | Which segmentation produced the regions: both models, one, a dragged box, or the centre of the frame |

The full glossary is [`00-overview/glossary.md`](00-overview/glossary.md).

## 7. Features

### 7.1 Capture

- A full-screen camera preview with one shutter button and a flash switch (off by default).
- A **RAW** switch (off by default, remembered): the photo is captured as a DNG and corrected
  from the sensor data with the camera's own colour matrices (D23). Disabled, with the reason,
  on cameras that cannot capture RAW.
- **Open a photo** from the system photo picker. Imported photos go through the same
  pipeline and are marked as imported (D17). The picker needs no permission.
- The camera permission is asked for on the capture screen. If it is refused, *Open a photo*
  still works.
- After the shutter, a progress view names the step: finding garments, reading the light,
  correcting. The result opens when it is done.

### 7.2 Correction

- The image is decoded to linear light, the light is estimated, one Bradford adaptation to
  D65 is applied to every pixel, and the result is re-encoded as an sRGB JPEG.
- The output keeps the input's resolution. Above 16 MP it is scaled down to 16 MP and the
  result says so (D14).
- When there is not enough of the scene left to read the light from, the photo is left as
  the phone made it and the result says so, rather than guessing (D7).

### 7.3 Finding the garments

- Two pretrained models, used for different jobs (D11, D13):
  - **MediaPipe Selfie Multiclass** separates background, hair, skin and clothes. It decides
    which pixels may inform the light estimate.
  - **SegFormer B2 clothes** separates top, skirt, trousers, dress, scarf and accessories. It
    gives each garment its own region.
- If either model fails to load or run, the other is used alone. If both fail, or no garment
  is found, the person can **drag a box** around a garment, and GrabCut cuts the garment out of
  it; without a box, the centre of the frame is cut out the same way (D24).
- Garment pixels **never** inform the light estimate. That is the whole reason the app
  segments at all (D5).

### 7.4 The garment readout

For each garment, largest first: its name (top, skirt, trousers, dress, scarf, clothing,
selected area), a swatch, the hex, the Lab values, and how much of the frame it covers.
Tapping a garment outlines it on the photo. A garment that is mostly overexposed says
*Overexposed — colour unreliable*.

### 7.5 Confidence and the neutral tap

- High confidence: a small *Confident* marker.
- Low confidence: a notice that says why — the estimators disagree, or too little of the
  scene is left once the garments are taken out — and offers **Tap something white or grey**.
- A neutral tap re-corrects the photo from that spot. The result is marked *Set from the
  spot you tapped*, with **Use the estimate** to go back.
- A tap on a spot that is blown out or too dark is refused with the reason.
- Every tap is recorded as an adjustment (D19).

### 7.6 Before and after

A toggle swaps the corrected photo for the original, in place.

### 7.7 History

Every capture is kept on the phone, newest first, with a thumbnail, its date, its garment
swatches and its confidence. A capture can be opened again or deleted. Deleting removes its
images and its rows.

### 7.8 Save to gallery

The corrected photo is copied into *Pictures/Garment Colour* on request. Nothing is saved to
the gallery otherwise.

### 7.9 Export

Settings → **Export data** writes one JSON file, to a place the person picks, with every
capture's light, confidence, garments and adjustments. No images. Nothing is exported unless
asked (D16).

### 7.10 First launch and privacy

A one-time card on the first launch: what the app does, that nothing leaves the phone, and
that a low-confidence result can be fixed by tapping something white or grey. Settings
repeats the privacy statement and shows whether each model loaded.

## 8. Screens

| | Screen | Purpose |
|---|---|---|
| S1 | Capture | Preview, shutter, flash, open a photo, progress |
| S2 | Result | Corrected photo, before/after, confidence, neutral tap, box, garments, details, save, delete |
| S3 | History | Every capture |
| S4 | Settings | Privacy, models, export, version, licences |

Specified in [`03-frontend/screens.md`](03-frontend/screens.md).

## 9. Constraints

- **Android 16 and above only**: `minSdk = targetSdk = compileSdk = 36` (D2). The plan said
  minSdk 26; the owner moved it to 16+.
- **Kotlin, Jetpack Compose, CameraX** (D1). No Flutter.
- **On the phone only** (D3). No `INTERNET` permission in any build.
- Both models inside the APK (D3, D12), converted from published weights. Nothing is trained.
- The colour maths lives in a module with no Android dependency (D10), proven against a
  Python reference on shared fixtures.

## 10. Phases and gates

From the plan. Each phase ends at a gate, so that a light estimator that cannot work is found
before a camera UI is built on top of it.

| Phase | What gets built | Gate | State |
|---|---|---|---|
| 0. Python spike | Estimators + Bradford, measured against ground truth | Median angular error clearly beats gray-world | Built; run on synthetic scenes only. **Not yet run on a public benchmark** |
| 1. Adversarial set | The owner's own shots: one garment filling the frame, mixed light | The dominant-garment case measured, mitigation chosen | Needs the owner's photos. Mitigation designed in (D5) |
| 2. Kotlin port | `color-engine`: linearise, estimate, adapt, re-encode | Kotlin reproduces Python within ΔE 0.5 on shared fixtures | Built and checked |
| 3. Capture + correction | CameraX, the JPEG path, save | End-to-end offline on a clean install | Built; needs a phone |
| 4. Garment parsing | Both models, masks, garment pixels out of the estimate | Phase 1 cases pass | Built; needs phase 1 |
| 5. RAW path | CameraX RAW (DNG), sensor matrices, DNG colour pipeline | Measurable ΔE gain over JPEG | Built (D23); decoder checked on two real phone DNGs (OnePlus 6T, Xiaomi Poco X3); a Samsung compressed DNG is refused by name. **Gate needs a phone**; RAW is opt-in until then |
| 6. Confidence + override | Score, low-confidence state, neutral tap | A tap visibly fixes a wrong scene | Built; needs a phone |
| 7. Polish + ship | Readout, before/after, onboarding, privacy copy | 10 real users, no wrong-colour reports | Readout, toggle, onboarding, copy built. Play listing out of scope |

The plan's warning stands: phases 0 and 1 are the whole bet. If the adversarial set cannot
be made to pass, the honest move is to require a reference card.

## 11. The plan's open decisions, resolved

| Open decision | Resolved as | Why |
|---|---|---|
| Runtime for SegFormer | ONNX Runtime, with the ONNX file the model's own repository publishes, quantised to 8 bits (28.6 MB, 99.8 % agreement with fp32 on a test photo) | No PyTorch→TensorFlow hop at all; no PyTorch either (D11) |
| SegFormer in the APK or on demand | **In the APK** | Play Asset Delivery needs the Play Store; the APK is installed from GitHub, and "offline from first launch" then holds without a second download (D3) |
| RAW default or fallback | RAW built, opt-in; JPEG stays the default until RAW passes its gate | The owner asked for RAW now; the gate still decides the default (D23) |
| Low-confidence behaviour | Correct and warn, offer the neutral tap | As the plan's default (D7) |
| Neutral tap | Built | As the plan's default (D8) |
| Mixed lighting | One light per photo; known limitation | As the plan's default (D9) |

## 12. Risks

| Risk | What reduces it |
|---|---|
| The light estimate is wrong on real scenes | Garments excluded (D5), estimators compared, confidence shown, neutral tap. Measured on the owner's adversarial set before trusting it |
| The JPEG has already been toned by the phone | Decoded with the sRGB curve, which is not the phone's curve. The RAW switch removes it (D23) |
| 8-bit SegFormer is worse than fp32 | The script reports agreement against fp32: 99.80 % on the one real photo tried (models LLD §2–§3). More photos would say more |
| SegFormer's weights carry `license: other` | Fine for a personal side-loaded build; read the terms before any public listing (D12) |
| A 16 MP correction runs out of memory | Strip-wise processing, one bitmap held (pipeline LLD §4) |
