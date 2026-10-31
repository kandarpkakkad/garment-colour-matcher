# Camera — HLD

## Purpose

Get one full-quality JPEG of an outfit into the app, from the camera or the photo picker.

## Owns

- The preview and the shutter (CameraX), the flash switch.
- The camera permission request.
- The photo picker, and copying its file in.
- Where pending input lives until the pipeline takes it: `cache/pending/`.

## Key decisions

- **CameraX 1.5 with `ProcessCameraProvider`**, preview plus image capture. RAW is CameraX's
  own `OUTPUT_FORMAT_RAW`, written as a DNG (D23); no hand-written Camera2 session.
- **RAW is a switch**, off by default, disabled with a reason on cameras without RAW.
- **Maximise quality, not latency.** One photo per capture; a quarter of a second does not
  matter, compression artefacts do.
- **The phone's auto white balance stays on.** The JPEG path corrects what the phone produced.
  Locking AWB would only change which wrong guess is corrected.
- **The picker needs no permission**, so importing works whatever the camera permission says.

## Failure modes

| Failure | Outcome |
|---|---|
| Permission refused | The preview area explains, with a button to ask again or open the app's settings; *Open a photo* still works |
| No camera bound (in use elsewhere) | CameraX's error is shown in the preview area |
| Capture fails | The error is shown; nothing is saved |

## Non-goals

Manual exposure, focus or white-balance controls, zoom UI, video, RAW + JPEG pairs (the
*before* image is rendered from the DNG itself, D23).
