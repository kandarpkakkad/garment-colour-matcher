# Decisions

Numbered, never renumbered. The code cites them: `(D5)`. When one is reversed, it stays here
with *Superseded by Dn* and the reason.

## D1. Kotlin, Compose and CameraX — no cross-platform layer

The plan names them, and everything hard here is native anyway: the camera, Camera2 RAW
later, two on-device model runtimes, bitmap processing at 16 MP. A Flutter shell would carry
all of that across a bridge for no gain.

## D2. Android 16 and above only

`minSdk = targetSdk = compileSdk = 36`. The plan said 26; the owner set 16+. Every platform
API used is present, so there is no compat branch. AndroidX is still used where it *is* the
toolkit — Compose, CameraX, Room, Activity — not for back-porting.

## D3. On the phone only; both models inside the APK

No backend, no upload, no `INTERNET` permission in any build type. The plan's default was
Play Asset Delivery for SegFormer, but PAD needs the Play Store and this APK is installed
from GitHub; shipping the model in the APK is also the only way "works offline from first
launch" is literally true. The APK is split per ABI, so the cost is the models (16.4 MB +
28.6 MB) plus the two runtimes' native libraries for one ABI.

**A dependency asks for the network anyway.** MediaPipe Tasks pulls in Google's
`com.google.android.datatransport` (usage telemetry), whose manifest adds `INTERNET` and
`ACCESS_NETWORK_STATE`. Found 2026-11-01 by the build's own merged-manifest check. `INTERNET`
is removed in our manifest (`tools:node="remove"`), so the OS refuses every socket and the
telemetry cannot leave. `ACCESS_NETWORK_STATE` stays: it transmits nothing, and JobScheduler
demands it for the network-constrained jobs the library schedules. Excluding the library was
rejected: MediaPipe calls it, and a missing class would surface as a crash on a phone, where
nothing has been tested yet. **Unverified on a device:** that the library's failed uploads
stay quiet (they should be ordinary IO errors, retried with backoff).

## D4. The correction is one linear 3×3 matrix in linear sRGB

The plan says both "a per-channel linear scale in linear RGB" and "a Bradford adaptation, one
3×3 matrix". They reconcile as: the Bradford transform from the estimated white to D65,
written in XYZ, is conjugated into linear sRGB —
`C = M_XYZ→sRGB · Bradford(white → D65) · M_sRGB→XYZ` — and applied to every pixel.

Texture survives because any linear map commutes with shading: a fold that halves the light
on a pixel halves its corrected value too. A pure per-channel scale (von Kries in sRGB) is the
diagonal special case; Bradford is the better-behaved textbook choice, at the same cost.
Values pushed outside [0, 1] are clipped on encode.

## D5. Garment pixels never inform the light estimate

The plan's first mitigation, and the reason the app segments at all: a red dress filling
half the frame makes every gray-assumption method decide the light is red. The estimate is
read from background, hair and skin only, with clipped and near-black pixels removed too.
Each of those regions is capped at half the total weight, so no single region dominates
(the plan's second mitigation). Illuminant LLD §2.

## D6. Three estimators, combined; gray-world kept for comparison only

Shades-of-gray (p = 6), first-order gray-edge (p = 6, σ = 1), and the skin anchor when there
is enough skin. Their unit vectors are summed and normalised. Gray-world is computed and
shown in the details, but it never votes: it is the method most fooled by what is left. The
plan's learned CNN is skipped, as the plan advised: there is no pretrained mobile
checkpoint, and nothing in v1 is trained.

## D7. Low confidence: correct and warn

The plan's default. Confidence comes from the estimators' disagreement and how much of the
scene was left to read. Below the line, the photo is corrected anyway and the result says why
it is unsure. With no usable scene at all, the photo is **not** corrected (an identity
matrix): a guess from nothing is worse than the phone's own.

## D8. The neutral tap is built, and does not break the no-card promise

It asks the person to point at something already in the photo, not to carry a card. The
median of a small patch around the tap becomes the light.

## D9. One light per photo in v1

Per-region estimation is a v2 problem. Mixed light is a known limitation, said in the docs,
not hidden.

## D10. The colour maths is a pure Kotlin module, proven against Python

`:color-engine` has no Android dependency: images are float planes, labels are byte maps.
The app converts bitmaps at the edge. A Python reference in `spike/` implements the same
algorithms; it writes fixtures — small scenes with their expected outputs — that the Kotlin
tests must reproduce within ΔE2000 0.5 (the phase 2 gate). Change one side, change both.

## D11. Two runtimes: MediaPipe Tasks and ONNX Runtime

MediaPipe's selfie model ships as TFLite and runs under MediaPipe Tasks as published. For
SegFormer, the model's own Hugging Face repository already publishes an ONNX export, so there
is no PyTorch→TensorFlow conversion and no PyTorch: the only step is 8-bit dynamic
quantisation with ONNX Runtime's tooling. The plan's fear of a lost week was about a
conversion that turns out not to be needed.

Quantisation, measured 2026-11-01 on a photo of a person (models LLD §3): the weights are 68 %
`MatMul` and 32 % `Conv`, so both are quantised, with **unsigned** 8-bit weights — ONNX
Runtime has no CPU kernel for `ConvInteger` with signed weights and the model will not load.
110 MB → 28.6 MB, and 99.80 % of output cells agree with fp32. `MatMul` alone gave 54.8 MB
and 99.74 %.

## D12. Models are fetched and pinned by a script, not committed

`tool/models_in_docker.sh` downloads both by URL, checks their sha256, quantises SegFormer,
and writes them to `app/src/main/assets/models/`, which git ignores. CI caches the result.
A missing or broken model is a degraded app, never a crash (D20).

SegFormer's licence: the training code is MIT; the weights' model card says
`license: other` and states no terms, and the ATR dataset has research terms. That is fine for
a personal side-loaded build; read the terms before any public listing. (The plan said MIT;
on 2026-11-01 the card did not.)

## D13. How the two segmentations combine

SegFormer decides *which* garment; MediaPipe is a second opinion on *whether* a pixel is worn.
A pixel is kept out of the estimate if **either** model says it is worn — being wrong in that
direction only loses some scene. Reported garments are top, skirt, trousers, dress and scarf;
hats, sunglasses, belts, shoes and bags are accessories: excluded from the estimate, not
reported. With MediaPipe alone, everything worn is one garment, *clothing*. Parsing LLD §3.

## D14. Two resolutions

Estimation, segmentation and measurement run on a working image at most 512 px on its long
side, averaged in linear light. The correction is applied to the full image. JPEG inputs above
16 MP are decoded at 16 MP: a 50 MP bitmap is 200 MB, and the output would be no more
accurate. RAW renders at full sensor resolution up to 25 MP (a 16-bit sample is half the
memory of an ARGB pixel); above that, one output pixel per 2 × 2 Bayer quad (D23).

## D15. The JPEG path first; RAW after its gate

> **Superseded by D23** (2026-11-01): the owner asked for RAW to be built now. The JPEG path
> stays the default until RAW's gate is measured on a phone.

A JPEG has already been white-balanced and tone-mapped by the phone. It is decoded with the
sRGB curve, which is not the phone's curve; that error is accepted for v1. Camera2 RAW is
designed ([`raw/hld.md`](../02-domain/raw/hld.md)) and not built: its gate is a measured ΔE
gain over JPEG on identical scenes, which needs a phone and phase 1's shots.

## D16. Nothing leaves the phone unless the person sends it

Images live in app-private storage. *Save to gallery* and *Export data* are explicit actions.
Export is JSON with no images.

## D17. Imported photos are allowed

The photo picker feeds the same pipeline. It is how the adversarial set is tried on the phone,
and how a photo taken earlier is checked. Imports are marked, because the phone that took
them, and what it did to them, are unknown.

## D18. Colour is reported as measurement, never as a name

Lab and XYZ under D65, linear RGB, and a hex for display. No vocabulary file. "Family errors"
(navy read as black) are a test metric, not a UI feature.

## D19. Adjustments are recorded, not yet applied as a bias

The plan says adjustments feed a per-device bias over time. v1 records every tap with the
estimate it replaced, and applies nothing: one careless tap on a beige wall would otherwise
shift every later capture, and there is no data yet to say a bias exists.

## D20. Tier 3 is a dragged box or the centre of the frame; GrabCut is not built

> **Superseded by D24** (2026-11-01): GrabCut is built; the box is its input, not the region.

When no model produced a garment, the person drags a box around one; without a box, the
centre of the frame is taken as the garment and kept out of the estimate. GrabCut from the
box is deferred: the box alone measures the colour, and GrabCut is a few hundred lines that
only sharpen the edge of a region whose colour is averaged anyway.

## D21. Toolchain in Docker; every command time-limited

The host has Docker and nothing else for Android. Builds and tests run in throwaway
containers: the Android SDK 36 + JDK 21 image already used for Habit Force, and a Python
image for the spike and the models. Every script carries a `timeout`, kills and removes its
container when it fires, and exits 124. CI jobs carry `timeout-minutes`.

## D22. The UI chrome is achromatic

A colour app must not tint what it shows. Every surface, button and outline is grey; garment
swatches and the photo are the only colour on screen. Design system §1.

## D23. RAW is built: CameraX writes the DNG, the engine decodes it

- **Capture** with CameraX 1.5's `OUTPUT_FORMAT_RAW`, which writes a DNG through Android's own
  `DngCreator`. That needs `ProcessCameraProvider` with separate Preview and ImageCapture use
  cases — `LifecycleCameraController` cannot select an output format. The lens-shading map is
  requested through Camera2 interop, so `DngCreator` includes it as a gain map.
- **Decode** in `color-engine`: our own reader for the DNGs `DngCreator` writes (uncompressed
  16-bit Bayer, strips), checked against three real phone DNGs. Anything else is refused by
  name, never guessed at.
- **One colour pipeline.** The DNG is read into the *working space*: camera RGB through the
  camera's own matrices for D65 light (`T`). That is exactly what a JPEG with its white
  balance locked to daylight would be, so estimation, confidence, the neutral tap and
  measurement are the same code as the JPEG path. Only the correction differs: instead of
  Bradford in sRGB, it is the DNG standard's chain — the estimated light back to a camera
  neutral, white balance in camera space, `ForwardMatrix` interpolated by the light's colour
  temperature, Bradford D50 → D65. Still one 3 × 3 matrix per photo (D4).
- **Saturated sensor pixels render white.** A sensor-clipped pixel has no colour to recover;
  rendering it as white avoids the pink highlights of white-balancing a clipped channel. That
  is the one non-linear step, and only for pixels the sensor clipped.
- **Colorimetric, not pretty.** No tone curve: the output is linear light encoded as sRGB, with
  one exposure gain. It looks flatter than the phone's JPEG. The *before* image is the same
  rendering with the phone's own white point (`AsShotNeutral`), so before and after differ
  only in the light.
- **Opt-in.** A RAW switch on the capture screen, remembered; JPEG stays the default until
  RAW's gate — a measured ΔE gain on identical scenes — is passed on a phone. Cameras that
  cannot capture RAW show the switch disabled, with the reason.
- **Kotlin only.** DNG decoding and camera profiles have no Python twin: they are outside the
  parity contract of D10, and are tested against the DNG specification's properties and the
  real files instead.

## D24. GrabCut refines the box

GrabCut (Rother et al., 2004) on the working image scaled to at most 160 px: five-component
colour models for garment and background, 8-connected graph, Dinic's max-flow, four
iterations, then the largest connected piece. The box's surroundings become usable scene for
the light estimate instead of being thrown away with the box. If GrabCut keeps less than 5 %
of the box, the whole box is used, as before. It refines the centre box too when neither
model ran. Kotlin only, like D23: it decides regions, not colours.
