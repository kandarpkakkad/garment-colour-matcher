# Storage — LLD

Code: `app/.../data/{Entities,AppDatabase,CaptureStore,ExportJson}.kt` — the DAO is in
`AppDatabase.kt`, *Save to gallery* is `CaptureStore.saveToGallery`. Rows are built from
engine results by `pipeline/Records.kt`. Schema: [`schema.md`](schema.md).

## 1. `CaptureStore`

| Function | Does |
|---|---|
| `dir(id)`, `original(id)`, `corrected(id)`, `labels(id)` | Paths, derived from the id |
| `newPending()` | A fresh `cache/pending/<uuid>.jpg` |
| `insert(capture, garments)` | One transaction |
| `rerendered(capture, garments, adjustment)` | One transaction: update the capture, delete and reinsert its garments, insert the adjustment |
| `delete(id)` | Rows (cascade), then the directory |
| `sweep()` | Pipeline LLD §8 |
| `observeAll()` | `Flow<List<CaptureWithGarments>>`, newest first, for History |
| `observe(id)` | `Flow<CaptureWithGarments?>`, for Result |

Rows before directory on delete: if the app dies between them, the sweep removes the orphan
directory; the other order would leave a row pointing at nothing.

Foreign keys: Room enables `PRAGMA foreign_keys` for entities that declare them; cascades are
declared on both child tables.

## 2. Save to gallery

`MediaStore.Images.Media.EXTERNAL_CONTENT_URI`, `RELATIVE_PATH = Pictures/Garment Colour`,
`DISPLAY_NAME = garment-colour-<yyyyMMdd-HHmmss>.jpg`, `IS_PENDING = 1` while the bytes of
`corrected.jpg` are copied, then `0`. No permission is needed on Android 10+ for files the app
creates.

## 3. Export

`ActivityResultContracts.CreateDocument("application/json")`, default name
`garment-colour-<yyyyMMdd>.json`. `ExportJson.write(captures)` builds the text by hand
(no JSON library in the unit-tested path), escaping strings per RFC 8259:

```json
{
  "format": "garment-colour-export",
  "formatVersion": 1,
  "appVersion": "0.1.0",
  "exportedAt": "2026-11-01T10:20:30Z",
  "captures": [
    {
      "id": "…", "createdAt": "…", "source": "CAMERA", "tier": "BOTH",
      "estimate": { "rgb": [r, g, b] | null, "answers": "…", "disagreementDeg": d | null,
                    "support": s, "score": q, "level": "HIGH", "reason": null },
      "applied": { "rgb": [r, g, b], "xy": [x, y], "method": "ESTIMATED" },
      "engineVersion": 1,
      "garments": [ { "label": "TOP", "fraction": f, "box": [l, t, r, b],
                      "linearRgb": [..], "xyz": [..], "lab": [..], "hex": "#…", "clipped": false } ],
      "adjustments": [ { "createdAt": "…", "kind": "NEUTRAL_TAP", "tap": [u, v] | null,
                         "box": [..] | null, "before": [..], "after": [..], "angleDeg": a } ]
    }
  ]
}
```

Non-finite numbers are written as `null`; JSON has no NaN.
