# Storage — HLD

## Purpose

Keep every capture on the phone: its images as files, its numbers as rows.

## Owns

- `files/captures/<id>/` — `original.jpg`, `corrected.jpg`, `labels.bin`.
- The Room database `garment_colour.db`: `captures`, `garments`, `adjustments`.
- `CaptureStore`: the only code that writes, deletes or sweeps either.
- *Save to gallery* (MediaStore) and *Export data* (a JSON file through the system's
  create-document picker).

## Key decisions

- **App-private storage.** Nothing is visible to other apps until the person saves or exports
  it (D16).
- **Files first, rows last** (pipeline LLD §2). A row never points at a file not yet written.
- **Paths are derived from the id**, not stored. Moving the directory scheme is then a code
  change, not a migration.
- **The estimate is stored with the capture, not just the applied light**, so a neutral tap
  can be undone and the two compared (D19).
- **Deleting is total**: the directory and the rows (cascade). No undo; there is no backup
  (PRD §4).
- **Room, as the plan says,** with its schema exported to `app/schemas/` and committed, so a
  change to an entity shows in review.

## Failure modes

| Failure | Outcome |
|---|---|
| Files written, transaction fails | Directory deleted; error shown |
| Row present, files gone | Result says *image missing*; the row can be deleted |
| Directory with no row (killed before step 8) | Removed by the sweep on next start |

## Non-goals

Sync, backup, cloud (D16); a migration path from any earlier schema — version 1 is the first.
