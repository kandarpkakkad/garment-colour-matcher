# Design system

Code: `app/.../ui/theme/Theme.kt`, `app/.../ui/common/`.

## 1. No colour but the photo's (D22)

A colour app must not tint what it shows. Every surface, control and outline is grey.
The photo and the garment swatches are the only chromatic things on screen.

- **No dynamic colour.** Material You would tint every surface with the wallpaper's hue.
- **No chromatic error colour.** Warnings are an icon and words, not red.
- **The photo sits on mid-grey**, `#767676` (L* ≈ 50), in both themes, like a viewing booth:
  a white or black surround shifts how the eye reads the colours inside it.

## 2. Tokens

| Token | Light | Dark |
|---|---|---|
| `background` | `#F4F4F4` | `#121212` |
| `surface` | `#FFFFFF` | `#1C1C1C` |
| `surfaceVariant` | `#E6E6E6` | `#2A2A2A` |
| `onSurface` / `onBackground` | `#1A1A1A` | `#EDEDED` |
| `onSurfaceVariant` | `#555555` | `#B0B0B0` |
| `outline` | `#8A8A8A` | `#6E6E6E` |
| `primary` | `#2B2B2B` | `#E0E0E0` |
| `onPrimary` | `#FFFFFF` | `#121212` |
| `primaryContainer` | `#DADADA` | `#3A3A3A` |
| `onPrimaryContainer` | `#1A1A1A` | `#EDEDED` |
| `error` | `#1A1A1A` | `#EDEDED` |
| `stage` (photo surround) | `#767676` | `#767676` |

The theme follows the system's light or dark setting.

## 3. Type

Material 3's default scale. Hex and Lab values in `FontFamily.Monospace`, so columns of numbers
line up and a `0` is never read as an `O`.

## 4. Components

- **Swatch.** 48 dp square, 8 dp corners, filled with the garment's hex, 1 dp `outline`
  border so a white or black swatch still has an edge.
- **Garment row.** Swatch · name and share of frame · hex and `L a b` in monospace. An
  overexposed garment adds *Overexposed — colour unreliable* in `onSurfaceVariant`.
- **Highlight.** The selected garment's region drawn over the photo: everything else dimmed
  to 45 % black, the region untouched. No colour is added.
- **Confidence.** `HIGH`: a small outlined chip, *Confident*. `LOW`: a card with a warning icon,
  the reason (illuminant LLD §5) and the tap button. `USER`: a chip, *Set from the spot you
  tapped*, and *Use the estimate*.
- **Shutter.** A 72 dp white disc with a 4 dp grey ring; disabled at 40 % opacity.
