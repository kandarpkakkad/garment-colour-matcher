# Screens

## S1 Capture

Top bar: title *Garment Colour*; actions **History**, **Settings**.

| State | Shows |
|---|---|
| No permission | Stage-grey area: *The camera shows the outfit you want to check.* **Allow camera**. After a permanent refusal: **Open settings** |
| Ready | Preview; bottom bar: **Open a photo** · shutter · flash switch (*Flash off* / *Flash on*); above it a **RAW** switch (*RAW off* / *RAW on*). On a camera without RAW the switch is disabled and reads *This camera does not offer RAW* |
| Processing | Preview dimmed; a progress indicator with the step: *Reading the photo…*, *Finding garments…*, *Reading the light…*, *Correcting…*, *Saving…*. Shutter and picker disabled |
| Error | A snackbar with the message, e.g. *Could not read this photo: <reason>* |

First launch: a dialog —

> **Garment Colour** shows your clothes in their true colours.
>
> - Take a photo of an outfit. The app finds each garment and removes the colour cast of the
>   light, keeping folds and texture.
> - Everything happens on this phone. Nothing is uploaded; there is no account.
> - If it says it is unsure, tap something white or grey in the photo.
>
> **Got it**

## S2 Result

Top bar: back; title = the capture's date and time; actions **Save to gallery**, **Delete**.

1. **Photo** on the stage-grey surround, fit to width. Toggle under it:
   **Corrected** | **Original**. In tap mode the photo takes taps; in box mode, drags.
2. **Confidence** (design system §4). `LOW` card button: **Tap something white or grey**.
   In tap mode a bar reads *Tap something white or grey in the photo* with **Cancel**.
   A refused tap: *That spot is overexposed — tap something white or grey that isn't
   glaring* / *That spot is too dark — tap something lit*.
3. **Garments**, largest first. Tapping a row highlights it; again clears.
   - None found (tiers `people-only`/`both`/`clothes-only` with no garments, or `centre`):
     *No garment found.* **Drag a box around one**. In tiers `box` and `centre`, the button
     reads **Select a different area**.
4. **Details**, collapsed: *Light* ≈ CCT and xy of the applied light; each estimator's answer
   as an angle from the combined one; disagreement; *Scene used*: support as a percentage;
   *Found with*: the tier in words (*Both models*, *Clothes model only*, *People model only*,
   *Your box*, *Centre of the frame*); *Photo*: W × H, *from the camera* / *RAW from the
   camera* / *imported*, *scaled down from N MP* when it was; for RAW, *Exposure* ×gain;
   *Engine* N.

For RAW, *Original* is the DNG rendered with the phone's own white point (D23).

Delete asks: *Delete this capture? Its photos and colours are removed from this phone.*
**Delete** / **Cancel**.

Missing image: the photo area says *Image missing* and **Delete** still works.

## S3 History

Top bar: back; *History*. A list, newest first: corrected thumbnail (72 dp) · date and time ·
the garment swatches in a row · a marker for `LOW` (*Low confidence*) or `USER` (*Tapped*).
Empty: *Nothing yet. Captures you take appear here.*

## S4 Settings

Top bar: back; *Settings*.

- **Privacy.** *Photos and colours stay on this phone. Garment Colour has no internet
  permission, no account and no analytics. Save to gallery and Export data are the only ways
  anything leaves the app, and only when you use them.*
- **Models.** One line each: *People model (MediaPipe)* and *Clothes model (SegFormer)* —
  *Ready*, *Loading…*, *Not in this build* or *Failed: <reason>*.
- **Export data.** *Every capture's light and garment colours as a JSON file. No photos.*
- **About.** Version, engine version, model sources and licences (models HLD).
