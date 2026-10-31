# Working on Garment Colour

An offline Android app that photographs an outfit, finds each garment, and gives the photo
back with the garments' true colours — the light's cast removed, folds and weave kept. Kotlin,
Compose, CameraX, two on-device segmentation models, a pure-Kotlin colour engine checked
against a Python reference. No server, no sync, no account. Android 16+.

## Memory, first and last

**Read memory before you start. Write memory before you finish.** Every task, both ends. The
`neo4j-memory` MCP server holds what this repository cannot: what is half-finished, what broke
last time, what the owner has already decided against.

- **First**, `search_memory` — by keyword, or with no arguments to list everything. Do it
  before asking the owner a question and before writing code.
- **Last**, `save_memory` for something new, or `update_memory` for something already tracked.
  Do it before you report back, not after.

Memories about this project are the `garment-colour-*` namespace, hanging off the
`garment-colour` hub:

| id | holds |
|---|---|
| `garment-colour` | what the product is, its scope and its hard constraints |
| `garment-colour-accuracy` | measured results — benchmark runs, the owner's adversarial set — and what they decided |
| `garment-colour-device-findings` | what has actually been observed on a phone — and what has not |
| `garment-colour-release-pipeline` | workflows, signing keys, distribution |
| `garment-colour-working-agreements` | how the owner wants work done here |
| `garment-colour-state-<date>` | current state and open items. Dated, and the first thing to go stale |

**Keep the line between memory and the repository.** This file and `docs/` are authoritative
for conventions, design and decisions. Memory is for what has no home here.

**If the server is not connected, say so.** Its absence means it failed to start, not that
memory does not apply.

---

Read [`docs/README.md`](docs/README.md) first, then
[`docs/00-overview/decisions.md`](docs/00-overview/decisions.md). Decisions are numbered
(D1, D2 …) and the code cites them. When you touch something with a decision behind it, read
the decision.

---

## 1. Documents before code

**Write the design down first, then build to it.**

| | Holds | Never holds |
|---|---|---|
| `hld.md` | Purpose, what it owns, key decisions, failure modes, non-goals | Algorithms, DDL |
| `lld.md` | Algorithms, function contracts, edge cases, thresholds | The schema — it *references* `schema.md` |
| `schema.md` | Tables, stored file shapes, what is deliberately **not** stored | Anything repeated from the HLD |

The order of work: HLD → schema → LLD → code → `docs/README.md` if the built/not-built table
moves.

**Code is the source of truth; documents must reflect it.** When the implementation has to
diverge, change the document in the *same commit*, saying what you learned. A document
describing something never built is worse than none — the one exception is a design marked
**Not built** at its top, waiting on a gate (`docs/02-domain/raw/hld.md`).

---

## 2. How code is written here

### Comments explain why, never what

Cite the decision or the document: `(D5)`, `illuminant LLD §2`. Match the density of the file.

### Two languages, one algorithm

The Python in `spike/` and the Kotlin in `color-engine/` implement **the same algorithms,
line for line**. A change to estimation, correction or measurement changes **four** things in
one commit:

1. the LLD,
2. `spike/gcm/…`,
3. `color-engine/…`,
4. the fixtures — `tool/spike_in_docker.sh fixtures` — and `FixtureParityTest` must pass.

If they disagree, the fixtures say so. Do not loosen a parity tolerance to make a test pass;
find which side is wrong. Bump `Engine.VERSION` when the same photo would give different
numbers.

### The engine has no Android in it

`color-engine` is plain Kotlin/JVM (D10). Bitmaps become `LinearImage` and class maps become
`LabelMap` in `app/.../pipeline/Bitmaps.kt` and `Segmenters.kt`, and nowhere else.

### All colour maths in linear light

Decode at the edge, encode at the edge (D4). An average of encoded sRGB values is a bug.

### Never gate a feature on a check you cannot run

A model that fails to load drops the app to a lower tier; it never hides the camera. The
picker works without the camera permission. Platform detections belong in the *handling* of a
failure, never in the *decision to offer*.

### An error message says the reason, not the fact

`PipelineException` carries the decoder's or the model's message to the screen.

### Never interpolate a nullable into a string

Kotlin will print `null` into a label. Drop the clause or supply the word.

### Measured numbers stay measured

A number in the docs (an agreement percentage, an angular error) says where it came from and
when. Never round a synthetic result into a claim about real photos.

---

## 3. Schema changes

- Room, `AppDatabase` version 1, schema exported to `app/schemas/` (committed). A change is a
  new version with a `Migration`, never a destructive fallback — there is no backup (D16).
- `labels.bin` and the label codes are a stored format too: **never renumber a `Label`**.

---

## 4. Tests

```bash
tool/spike_in_docker.sh test       # the Python reference's self-checks
tool/test_in_docker.sh             # engine + app unit tests, incl. FixtureParityTest
tool/test_in_docker.sh engine      # the engine alone — the fast loop
tool/build_in_docker.sh            # every unit test, the APKs, and the INTERNET check
tool/spike_in_docker.sh bench      # the benchmark, when estimation changes
```

**Every command is time-limited** (D21). The scripts wrap Docker in `timeout` — 20 minutes for
the spike, 30 for tests and models, 60 for the build; `GCM_TIMEOUT=90m` overrides — kill and
remove the container when it fires, and exit 124. CI jobs carry `timeout-minutes`, and each
long step its own `timeout`. A wait loop is always bounded: `timeout 1500 bash -c 'until …'`,
never a bare `until`. Nothing should be able to run for ever on the owner's machine.

What is tested: the colour maths against published values (Sharma's ΔE2000 pairs, Lindbloom's
Bradford matrix), the estimators, fusion, measurement, the corrector, parity with Python, the
record mapping and the export. What is not: anything on a device. **There are no device tests**
— the owner tests on a phone.

When you change behaviour a screen triggers, **check the screen calls the thing you tested.**

---

## 5. Verifying, and what you may claim

- Say the number of tests, and the worst parity ΔE `FixtureParityTest` prints.
- The APK builds — say so, and say that is **all** it proves about the camera, the models
  and the screens.
- **Never describe device behaviour you have not observed.** Nothing has run on a phone until
  someone records otherwise in `garment-colour-device-findings`.
- **Synthetic accuracy is not accuracy.** Say "on synthetic scenes" every time.
- Report failures with their output. A skipped step is said out loud.
- **Verify locally. Do not watch CI.**

---

## 6. Build and release

- Every push to `main` bumps the patch version and builds a debug APK named for it. Docs-only
  pushes are skipped. Releasing is Actions → **Release** → level `minor`.
- The version lives in `version.properties` only; move it with `tool/bump_version.sh`.
- Models: `tool/models_in_docker.sh` before a local build; CI caches them keyed on
  `tool/models/fetch_models.py`. A changed upstream file fails its sha256 — check it, then
  update the pin deliberately.
- **Do not put the skip-ci marker in a commit message** — GitHub reads it anywhere. Refer to it
  by name.
- **A `run:` value must never begin with a quote.** Parse workflows before pushing.

---

## 7. Editing style

- Surgical diffs. Do not reformat files you are not changing.
- Remove dead code you have just orphaned, in the same change.
- Commit messages: a short imperative subject, then prose explaining *why*.

---

## 8. Things that are deliberately absent

| | Why |
|---|---|
| A server, an account, the `INTERNET` permission | D3. MediaPipe's telemetry dependency asks for it; the manifest strips it, and the build fails if it returns |
| Sync, backup, cloud | D16 |
| Colour names, palettes, matching | D18, PRD §4 |
| A learned illuminant model | D6 |
| A per-device bias applied to estimates | D19 |
| Per-region light | D9 |
| RAW as the default | D23: opt-in until a phone measures its ΔE gain |
| A Python twin for DNG decoding or GrabCut | D23, D24: outside the parity contract; tested against the DNG specification, real DNGs and synthetic scenes |
| Dynamic colour, chromatic UI | D22 |
| Minification | Both model runtimes reach into Java from native code (app/build.gradle.kts) |
