# Garments — HLD

## Purpose

Turn each garment region into one measured colour under D65: Lab, XYZ, linear RGB, and a hex
for the swatch.

## Owns

- Which regions are big enough to report.
- Which of a region's pixels are measured: not clipped, not black, and not its deepest
  shadows or brightest highlights.
- Applying the capture's correction to the measurement.
- The *overexposed* flag.

## Key decisions

- **Measure first, then correct.** The mean is taken in the uncorrected linear image and the
  correction matrix is applied to that mean. Because the correction is linear (D4) this is
  the corrected mean, without the per-pixel clipping the encode does.
- **Trim to the middle 80 % of luminance.** A garment's folds put its darkest pixels in
  shadow and its brightest in specular highlight; neither is the fabric's colour.
- **An overexposed garment is still reported**, flagged. A blown-out white shirt is exactly
  the garment someone wants to check, and saying *unreliable* is better than saying nothing.
- **Colour, never a name** (D18).

## Non-goals

Colour names, palettes, matching; per-instance garments; patterned fabric as more than one
colour (a striped shirt reports its average).
