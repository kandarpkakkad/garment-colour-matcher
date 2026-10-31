# Glossary

| Term | Meaning |
|---|---|
| **Adjustment** | A row recording something the person did to a capture's light: a neutral tap, a box, going back to the estimate (D19) |
| **Angular error** | The angle, in degrees, between two illuminant vectors in linear RGB. The colour-constancy literature's standard measure of how wrong an estimate is |
| **Bradford** | A chromatic adaptation transform: a 3×3 matrix that maps colours seen under one white to how they would look under another. Textbook, no parameters |
| **Capture** | One photo and everything derived from it |
| **Clipped** | A pixel with any channel at or above 0.95 in linear light. Its true colour is unknown, so it is kept out of the estimate and out of measurements |
| **Combined estimate** | The normalised sum of the estimators that produced an answer (D6) |
| **Confidence** | `high`, `low` or `user`, with a score in [0, 1]. Illuminant LLD §5 |
| **D65** | The CIE standard daylight white, x = 0.3127, y = 0.3290. The light the corrected photo is rendered under |
| **Disagreement** | The largest angle between any two estimators that answered |
| **ΔE2000** | The CIE 2000 colour difference. Under 1 is invisible; 1–2 visible on close inspection; over 5 reads as a different colour |
| **Family error** | A corrected colour that lands in a different coarse colour family from the truth — navy read as black. Accuracy LLD §4 |
| **Fixture** | A small scene the Python spike writes with its expected outputs, which the Kotlin engine must reproduce (D10) |
| **Garment** | A reported worn region: top, skirt, trousers, dress, scarf, clothing, or selected area |
| **Gray-edge** | Estimator assuming the image's *edges* average to neutral |
| **Gray-world** | Estimator assuming the scene averages to neutral. Kept for comparison only (D6) |
| **Illuminant** | The colour of the light, as a unit vector in the camera's linear RGB — for the JPEG path, linear sRGB |
| **Label map** | One byte per working-image pixel naming its region. Parsing LLD §1 |
| **Linear light** | Pixel values proportional to light, after undoing the sRGB curve. All colour maths happens here |
| **Neutral tap** | Tapping something white or grey so that its colour becomes the light (D8) |
| **Region** | Background, hair or skin: the parts of the scene the estimate may use (D5) |
| **Shades-of-gray** | Gray-world with a Minkowski p-norm (p = 6) instead of the mean, weighting brighter pixels more |
| **Skin anchor** | Estimator using the narrow band that skin chromaticity occupies |
| **Support** | The share of the working image the estimate could use |
| **Tier** | Which segmentation produced the label map: `both`, `clothes-only`, `people-only`, `box`, `centre` |
| **Usable pixel** | A pixel in background, hair or skin that is neither clipped nor near-black |
| **Working image** | The ≤ 512 px linear copy every measurement is made on (D14) |
