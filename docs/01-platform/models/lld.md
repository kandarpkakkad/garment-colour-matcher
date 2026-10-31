# Models — LLD

Code: `tool/models_in_docker.sh`, `tool/models/fetch_models.py`,
`app/.../models/{Segmenters,MediaPipeSegmenter,SegformerSegmenter}.kt`.

## 1. Files

| File in `app/src/main/assets/models/` | From | Check |
|---|---|---|
| `selfie_multiclass_256x256.tflite` | `https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite` | sha256 pinned in `fetch_models.py` |
| `segformer_b2_clothes_int8.onnx` | `https://huggingface.co/mattmdjaga/segformer_b2_clothes/resolve/584abc1e1d260e23c0fc627c5217a09b2b461046/onnx/model.onnx` → quantised | source sha256 `a93a8dac…d54f` pinned; output size and agreement reported |
| `MODELS.txt` | written by the script | sources, sha256 of every file, quantisation report |

The MediaPipe URL says `latest`; the sha256 is what pins it. If Google ever replaces the file,
the script fails loudly rather than shipping a different model.

## 2. The script

`tool/models_in_docker.sh` runs `fetch_models.py` in `python:3.12-slim` with `numpy`, `onnx`
and `onnxruntime` from pip, time-limited (D21). Downloads are cached in a volume, so a re-run
re-verifies without downloading. Steps:

1. Download each file to the cache unless present; verify sha256; stop on mismatch.
2. Quantise SegFormer: `onnxruntime.quantization.quantize_dynamic(src, dst,
   op_types_to_quantize=["MatMul", "Conv"], weight_type=QUInt8)`.
3. Check (§3).
4. Copy both models and `MODELS.txt` into the assets directory.

Why those arguments (measured 2026-11-01, ONNX Runtime 1.20.1, the model card's example
photo of a person):

| Variant | Size | Agrees with fp32 | |
|---|---|---|---|
| `MatMul`, int8 weights | 54.8 MB | 99.74 % | Conv weights (32 % of the model) left in fp32 |
| **`MatMul` + `Conv`, uint8 weights** | **28.6 MB** | **99.80 %** | Chosen |
| `MatMul` + `Conv`, int8 weights | 28.6 MB | — | Will not load: no CPU `ConvInteger` kernel for signed weights |

The asset keeps the name `segformer_b2_clothes_int8.onnx` — "int8" in the loose sense of
8-bit integer; its weights are unsigned.

## 3. The quantisation check

fp32 and 8-bit sessions run on the same inputs; the report gives, per input, the share of
output cells (128 × 128) whose argmax class agrees. Inputs: a fixed synthetic image always,
plus any images passed with `--check-image`. The synthetic image only proves the model runs
and the shapes are right (it agreed on 90.8 %: there is no person in it, so most cells are
ambiguous); agreement on a real photo of a person is the number that matters.

The timings it prints are the Docker host's CPU, not a phone's. On-device speed has not been
measured.

## 4. In the app

```kotlin
interface Segmenter { fun segment(small: Bitmap): ByteArray }   // raw classes, w·h bytes
```

**MediaPipe.** `ImageSegmenter.createFromOptions` with the asset path, running mode `IMAGE`,
category mask on, confidence masks off. `segment` wraps the bitmap in an `MPImage`, takes the
category mask, extracts its bytes, and resamples nearest-neighbour to `w × h` if the mask came
back another size.

**SegFormer.** `OrtEnvironment.getEnvironment()`, a session from the asset's bytes, intra-op
threads 4. `segment` scales the bitmap to 512 × 512 (bilinear), builds the tensor with
`Fusion.segformerInput`, runs, reads the `[1, 18, 128, 128]` output as a float buffer, and
returns `Fusion.segformerArgmax(logits, w, h)`.

`Segmenters` creates both on a background thread at app start, records each one's status
(`Loading`, `Ready(millis)`, `Missing`, `Failed(message)`), and lets the pipeline wait for
loading to finish. Calls are serialised by a mutex: one capture at a time.

## 5. Build

- `androidResources { noCompress += listOf("tflite", "onnx") }` — MediaPipe maps its model
  from the APK and cannot from a compressed entry.
- ABI splits: `arm64-v8a` (phones) and `x86_64` (emulators). Both runtimes ship native
  libraries per ABI.
