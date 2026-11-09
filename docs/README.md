# Garment Colour — documents

Start with the [PRD](PRD.md), then the [overview HLD](00-overview/hld.md) and the
[decisions](00-overview/decisions.md).

| | |
|---|---|
| [`PRD.md`](PRD.md) | What v1 is, for whom, and the phases |
| **00-overview** | [`hld.md`](00-overview/hld.md) · [`decisions.md`](00-overview/decisions.md) (D1–D24) · [`glossary.md`](00-overview/glossary.md) |
| **01-platform** | camera [hld](01-platform/camera/hld.md) [lld](01-platform/camera/lld.md) · models [hld](01-platform/models/hld.md) [lld](01-platform/models/lld.md) · storage [hld](01-platform/storage/hld.md) [lld](01-platform/storage/lld.md) [schema](01-platform/storage/schema.md) · versioning [lld](01-platform/versioning/lld.md) |
| **02-domain** | colour [hld](02-domain/colour/hld.md) [lld](02-domain/colour/lld.md) · illuminant [hld](02-domain/illuminant/hld.md) [lld](02-domain/illuminant/lld.md) · parsing [hld](02-domain/parsing/hld.md) [lld](02-domain/parsing/lld.md) · garments [hld](02-domain/garments/hld.md) [lld](02-domain/garments/lld.md) · pipeline [hld](02-domain/pipeline/hld.md) [lld](02-domain/pipeline/lld.md) · RAW [hld](02-domain/raw/hld.md) [lld](02-domain/raw/lld.md) |
| **03-frontend** | [`design-system.md`](03-frontend/design-system.md) · [`navigation.md`](03-frontend/navigation.md) · [`screens.md`](03-frontend/screens.md) |
| **04-accuracy** | [hld](04-accuracy/hld.md) (metrics, gates, **results so far**) · [lld](04-accuracy/lld.md) (spike, fixtures, benchmark) |

## Built, and what has been checked

As of 2026-11-01.

| Part | State | Checked by |
|---|---|---|
| Python reference (`spike/`) | Built | 13 self-checks, incl. Sharma ΔE2000 and Bradford |
| Synthetic benchmark | Built, run | Accuracy HLD, results |
| Public-benchmark runner | Built | Tested on a generated dataset of the same shape. **Never run on a real dataset** |
| Colour engine (`color-engine/`) | Built | 51 unit tests; **FixtureParityTest: Kotlin matches Python on 13 fixtures, worst garment ΔE2000 0.000001 (phase 2 gate: 0.5)** |
| RAW path — DNG reader, camera profile, render (D23) | Built | Unit tests on synthetic DNGs, including a simulated tungsten scene corrected to neutral; **two real phone DNGs decoded and rendered, inspected by eye** (raw HLD, *Checked*). **Never captured on a phone** |
| GrabCut (D24) | Built | Unit tests: max-flow on the textbook network, a cut-out with IoU ≥ 0.9, determinism, the fallback |
| Models | Fetched, pinned, quantised, in the APK | sha256 pins; 8-bit SegFormer agrees with fp32 on 99.80 % of one real photo |
| App: capture, pipeline, result, history, settings | Built | Debug APKs build (arm64 113.6 MB); 4 unit tests (record mapping, export); merged manifest has no `INTERNET`. **Never run on a phone** |
| Camera, both model runtimes on Android | Built | **Never run on a phone** |
| RAW's gate — ΔE gain over JPEG on identical scenes | **Not run** — needs a phone | — |
| The owner's adversarial set (phase 1) | **Not started** — needs photos | — |
| CI workflows | Both run on GitHub | Debug build passed 2026-11-01 and on every push since: models against their sha256 pins, the Python checks, every unit test incl. parity, the no-`INTERNET` check. Signing secrets set 2026-11-09. First release **v0.2.0** (2026-11-09): signed with the release key, SHA-1 `35:10:D4:…:DC:45`, both models inside, no `INTERNET`. **Never installed on a phone** |
