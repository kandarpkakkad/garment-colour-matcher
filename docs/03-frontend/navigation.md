# Navigation

Code: `app/.../ui/AppRoot.kt`. Navigation Compose, string routes.

| Route | Screen | Reached from |
|---|---|---|
| `capture` | S1 Capture — the start destination | Launch |
| `result/{id}` | S2 Result | Capture, when processing finishes; History |
| `history` | S3 History | Capture's top bar |
| `settings` | S4 Settings | Capture's top bar |

- Back from Result returns to wherever it was opened from.
- Deleting a capture on Result pops back.
- The first-launch card (PRD §7.10) is a dialog over Capture, shown until dismissed once
  (`SharedPreferences`, key `onboarded`).
- No deep links, no bottom bar: four screens, one of which is the camera.
