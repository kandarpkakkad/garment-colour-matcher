# Pipeline — HLD

## Purpose

Take one photo from the camera or the picker to a saved capture, and re-render a saved
capture when the person taps a neutral, drags a box, or goes back to the estimate.

## Owns

- The order of the steps (overview HLD §3) and which resolution each runs at (D14).
- Moving pixels between Android bitmaps and engine types — the only place that happens.
- Memory: one full-size bitmap at a time, processed in strips.
- Progress reported to the capture screen.
- Writing files before rows, so a crash never leaves a row pointing at nothing.

## Key decisions

- **One function renders, four callers.** A new capture, a neutral tap, a box and *Use the
  estimate* all end in the same render step. There is no second way a corrected image is
  made.
- **The estimate is recomputed on every render.** It is deterministic and costs tens of
  milliseconds on the working image; storing intermediate state to skip it would be one more
  thing to go stale.
- **Segmentation runs once per capture.** Re-renders read `labels.bin`; only a box replaces
  it.
- **A box resets the light to the estimate.** The box changes which pixels the estimate may
  read, so a tap made against the old regions no longer means what it did.

## Failure modes

| Failure | Outcome |
|---|---|
| Decode fails (corrupt file, unsupported format) | *Could not read this photo*, with the decoder's message; nothing saved |
| A model throws | Treated as absent for this photo (parsing HLD) |
| Disk full while saving | The capture's directory is removed, no row is written, the error is shown |
| Killed mid-way | Pending input and half-written directories are swept on the next start |

## Non-goals

Background processing after the app is closed; batch import; RAW from the picker (a picked DNG takes the JPEG path through Android's own decoder).
