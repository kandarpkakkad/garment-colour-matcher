# Models — HLD

## Purpose

Get two published segmentation models into the APK, pinned and verified, and run them on the
phone.

## Owns

- `tool/models_in_docker.sh`: download, verify, quantise, write to
  `app/src/main/assets/models/` (D12).
- Loading each model at start, on a background thread, and reporting its status.
- Running each on the working bitmap and returning **raw class maps**. What the classes mean
  is the engine's business (parsing LLD §2).

## The two models

| | MediaPipe Selfie Multiclass | SegFormer B2 clothes |
|---|---|---|
| Job | Worn vs scene, skin, hair (D13) | Which garment (D13) |
| Source | Google's MediaPipe model storage | `mattmdjaga/segformer_b2_clothes` on Hugging Face, the repository's own `onnx/model.onnx` |
| Shipped as | As published, TFLite, 16.4 MB | 8-bit dynamic quantisation of `MatMul` and `Conv` weights (uint8), ONNX, 28.6 MB |
| Runtime | MediaPipe Tasks Vision, `ImageSegmenter` | ONNX Runtime for Android |
| Input | The working bitmap; MediaPipe resizes to 256 × 256 itself | 512 × 512, normalised (parsing LLD §4) |
| Licence | Apache 2.0 (model card) | Training code MIT; weights card `license: other`; ATR dataset research terms (D12) |

## Key decisions

- **No PyTorch, no TensorFlow conversion** (D11). The only transformation is quantisation,
  with ONNX Runtime's own tool, reported against fp32.
- **Pinned by content.** Each download is checked against a sha256 in the script; the
  Hugging Face URL names a commit, not `main`.
- **Not committed** (D12). `assets/models/` is in `.gitignore`. CI restores it from a cache
  keyed on the script's hash.
- **A model that fails is absent, not fatal.** The app runs at a lower tier (parsing HLD) and
  Settings says which model and why.
- **No `labels.json`.** The plan listed one; the class lists live in code
  (`Fusion`), where a test can see them.

## What MediaPipe brings with it

MediaPipe Tasks depends on Google's `datatransport` usage telemetry, which asks for
`INTERNET`. The app's manifest strips that permission (D3); the build fails if it comes back.

## Failure modes

| Failure | Outcome |
|---|---|
| Model file not in the APK (built without running the script) | Status *missing*; tier 3 or the other model |
| Session creation throws (bad file, unsupported op) | Status *failed: <message>* |
| Inference throws on a photo | That photo's tier drops; logged |
