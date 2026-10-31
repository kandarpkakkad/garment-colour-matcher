# Storage — schema

Room, database `garment_colour.db`, version 1. Exported JSON schema:
`app/schemas/com.garmentcolour.app.data.AppDatabase/1.json`. Enums are stored by name.

Version 1 was edited in place on 2026-11-01 to add `exposure_gain` and RAW: no build had ever
been installed, so there was no data to migrate. From the first install on, every change is a
new version with a migration (AGENTS.md §3).

## `captures`

| Column | Type | Meaning |
|---|---|---|
| `id` | TEXT PK | Random UUID; also the directory name |
| `created_at` | INTEGER | Epoch ms |
| `source` | TEXT | `CAMERA`, `IMPORT` (D17) |
| `capture_path` | TEXT | `JPEG`, `RAW` (D23) |
| `width`, `height` | INTEGER | Of the corrected image |
| `source_megapixels` | REAL | Of the decoded source before any cap (RAW: the default crop) |
| `downscaled` | INTEGER | 1 if capped at 16 MP, or RAW rendered half-size (D14) |
| `exposure_gain` | REAL | RAW's exposure gain (raw LLD §6); 1 for JPEG |
| `tier` | TEXT | `BOTH`, `CLOTHES_ONLY`, `PEOPLE_ONLY`, `BOX`, `CENTRE` |
| `est_r`, `est_g`, `est_b` | REAL NULL | The combined estimate; NULL when there was none |
| `est_answers` | TEXT | Every estimator's answer (§4) |
| `est_disagreement` | REAL NULL | Degrees |
| `est_support` | REAL | |
| `est_score` | REAL | |
| `est_level` | TEXT | `HIGH`, `LOW` |
| `est_reason` | TEXT NULL | `NO_SCENE`, `LITTLE_SCENE`, `DISAGREE`, `ONE_ESTIMATOR` |
| `applied_r`, `applied_g`, `applied_b` | REAL | The light the correction used |
| `applied_method` | TEXT | `ESTIMATED`, `NEUTRAL_TAP`, `NONE` |
| `applied_x`, `applied_y` | REAL | xy chromaticity of the applied light |
| `engine_version` | INTEGER | `Engine.VERSION` that rendered it |

The confidence shown is `USER` when `applied_method = NEUTRAL_TAP`, else `est_level`.

## `garments`

| Column | Type | Meaning |
|---|---|---|
| `id` | INTEGER PK autoincrement | |
| `capture_id` | TEXT | FK → `captures.id`, `ON DELETE CASCADE`; indexed |
| `rank` | INTEGER | 0 = largest |
| `label` | TEXT | Label name (parsing LLD §1) |
| `fraction` | REAL | Share of the frame |
| `box_l`, `box_t`, `box_r`, `box_b` | REAL | Bounds, as fractions |
| `lin_r`, `lin_g`, `lin_b` | REAL | Corrected linear RGB |
| `xyz_x`, `xyz_y`, `xyz_z` | REAL | XYZ, D65 |
| `lab_l`, `lab_a`, `lab_b` | REAL | Lab, D65 |
| `hex` | TEXT | `#RRGGBB` |
| `clipped` | INTEGER | 1 when mostly overexposed |

## `adjustments`

| Column | Type | Meaning |
|---|---|---|
| `id` | INTEGER PK autoincrement | |
| `capture_id` | TEXT | FK → `captures.id`, `ON DELETE CASCADE`; indexed |
| `created_at` | INTEGER | Epoch ms |
| `kind` | TEXT | `NEUTRAL_TAP`, `USE_ESTIMATE`, `BOX` |
| `tap_u`, `tap_v` | REAL NULL | The tap, as fractions (`NEUTRAL_TAP`) |
| `box_l`, `box_t`, `box_r`, `box_b` | REAL NULL | The box (`BOX`) |
| `before_r`, `before_g`, `before_b` | REAL | Applied light before |
| `after_r`, `after_g`, `after_b` | REAL | Applied light after |
| `angle_deg` | REAL | Between before and after |

## Files

`files/captures/<id>/`:

| File | What |
|---|---|
| `original.jpg` | JPEG path: the input, byte for byte (an import keeps its bytes, whatever its format). RAW: the as-shot rendering of the DNG, the *before* image |
| `original.dng` | RAW only: the DNG as CameraX wrote it — the source every re-render reads |
| `corrected.jpg` | sRGB JPEG, quality 95 |
| `labels.bin` | The working label map (parsing LLD §6) |

`cache/pending/<uuid>.jpg` or `.dng`: camera output waiting for the pipeline. Swept on start.

## 4. `est_answers`

`;`-separated, in this order, each `NAME r g b` or `NAME -` when it did not answer:

```
GRAY_WORLD 0.61 0.58 0.53;SHADES_OF_GRAY 0.63 0.57 0.52;GRAY_EDGE 0.60 0.58 0.55;SKIN -
```

Numbers with up to 6 decimals. Written and read by `EstimateCodec` in the engine (tested).

## Deliberately not stored

| | Why |
|---|---|
| File paths | Derived from the id (storage HLD) |
| The correction matrix | Derived from `applied_*` (colour LLD §3) |
| The working image | Rebuilt from `original.jpg`; deterministic |
| Colour names | D18 |
| A per-device bias | D19 |
