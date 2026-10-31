# Colour — HLD

## Purpose

The settled maths: moving between 8-bit sRGB, linear light, XYZ and Lab; adapting one white
to another; measuring differences. Nothing here is estimated or tuned.

## Owns

- The sRGB transfer curve, both ways, and the lookup tables the app uses for speed.
- The sRGB↔XYZ matrices for D65, the D65 white, Lab, xy chromaticity, a CCT approximation for
  display.
- The Bradford adaptation and the **correction matrix** built from an illuminant (D4).
- ΔE2000 and angular error.
- The hex shown on screen.

## Key decisions

- All colour maths happens in linear light (D4). Encoded values only exist at the edges.
- One correction matrix per capture, conjugated into linear sRGB, applied to every pixel (D4).
- An illuminant equal to linear-sRGB white `(1, 1, 1)` gives the identity matrix. That is how
  "do not correct" is expressed (D7), with no special case.
- Values outside [0, 1] after correction are clipped on encode, per channel. Saturated
  garments can lose some chroma this way; measurements are taken before the clip (garments
  LLD).

## Failure modes

None at run time: every function is total. Degenerate inputs (a zero illuminant) are refused
by the caller (illuminant LLD), never reach here.

## Non-goals

Wide-gamut output (Display P3), ICC profiles, HDR. The output is an sRGB JPEG.
