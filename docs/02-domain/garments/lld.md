# Garments — LLD

Code: `color-engine/.../Garments.kt`. Reference: `spike/gcm/garments.py`.

Input: the working image (linear, uncorrected), its label map, and the correction matrix `C`.
`N = w · h`.

For each label whose role is **garment** (parsing LLD §1), in code order:

1. `P` = every pixel with that label. Skip the label if `|P| < max(30, 0.005 · N)`.
2. `fraction = |P| / N`. Bounds: the smallest box holding `P`, as fractions
   `(xmin / w, ymin / h, (xmax + 1) / w, (ymax + 1) / h)`.
3. `Q` = pixels of `P` neither clipped nor dark (illuminant LLD §1).
   - If `|Q| ≥ max(30, 0.5 · |P|)`: measure `Q`; `clipped = false`.
   - Else: `Q` = pixels of `P` that are not dark; `clipped = true`. If that is empty, skip
     the label.
4. Luminance `Y` of each pixel of `Q` (colour LLD §2). Sort ascending into `Ys`;
   `lo = Ys[⌊0.10 · (|Q| − 1)⌋]`, `hi = Ys[⌊0.90 · (|Q| − 1)⌋]`.
   `T` = pixels of `Q` with `lo ≤ Y ≤ hi`.
5. `mean` = the per-channel mean of `T` (linear, uncorrected).
6. `rgb = C · mean`, each channel clamped to ≥ 0. This is the **corrected linear RGB**.
7. `XYZ = M · rgb`; `Lab` from XYZ with white D65; `hex` from `rgb` (colour LLD §2).

Output sorted by `fraction`, largest first; ties by label code.

## Edge cases

- **Two garments the same class** (two tops) are one region and one colour.
- **A patterned garment** reports its trimmed average. Said in the PRD.
- **Garment entirely in shadow** (all dark): skipped, because nothing measurable is left.
- **`|Q|` small but above zero** after the fallback: measured anyway, flagged `clipped`.
