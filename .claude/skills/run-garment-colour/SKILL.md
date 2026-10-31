---
name: run-garment-colour
description: Test, benchmark, build, install and inspect Garment Colour (Kotlin + Compose + CameraX, Android 16+, colour engine checked against a Python reference) from this Windows machine, which has Docker but no JDK, Android SDK or Python. Use when asked to run the tests or the benchmark, regenerate fixtures, fetch the models, build the APK, install it on a phone, or read what the app stored on the device.
---

# Running Garment Colour

## The facts everything depends on

| | |
|---|---|
| Project | `C:\Users\DELL\Downloads\Github\garment-colour-matcher` |
| Host | Windows 11, Git Bash. **No JDK, Android SDK or Python on the host** |
| Android toolchain | Docker image `instrumentisto/flutter:3.38.10` — JDK 21, Android SDK 36 (Flutter unused) |
| Python | Docker image `python:3.12-slim` |
| Package | `com.garmentcolour.app` (release), `com.garmentcolour.app.debug` (debug) |
| Device | A real Android 16 phone. **No emulator is set up** |

**Every script is time-limited** — spike 20 min, tests and models 30, build 60. When the limit
hits, the container is killed and removed and the script exits 124. Raise it for one run with
`GCM_TIMEOUT=90m`. Any wait you write yourself is bounded too:
`timeout 1500 bash -c 'until …; do sleep 5; done'`.

Git Bash rewrites `/src/...` arguments into Windows paths; `tool/lib/docker_run.sh` sets
`MSYS_NO_PATHCONV=1` for the scripts. Set it yourself for any `docker run` you type.

## The loop for colour work

```bash
cd /c/Users/DELL/Downloads/Github/garment-colour-matcher
tool/spike_in_docker.sh test        # Python reference self-checks (13)
tool/spike_in_docker.sh fixtures    # after changing an algorithm on BOTH sides
tool/test_in_docker.sh engine       # Kotlin engine, incl. FixtureParityTest
tool/spike_in_docker.sh bench       # synthetic benchmark; --seeds N --size WxH
```

`FixtureParityTest` prints `worst garment ΔE2000`. It was 0.000001 when written; anything
approaching the 0.5 gate means the two sides have drifted.

## RAW on real DNGs

```bash
tool/test_in_docker.sh dng
```

Runs every `.dng` in `build/check/dng/` (git-ignored) through decode, working image, estimate
and render (`RealDngCheck`), and writes 900 px previews of the as-shot and corrected renders
to `build/check/dng/out/` — look at them. The three samples used on 2026-11-01 came from
raw.pixls.us (CC0): `samsung/SM-S901E/20250305_173647.dng`,
`OnePlus/ONEPLUS A6003/IMG_20201019_093659.dng`, `Xiaomi/M2010J19CG/IMG_20201224_141323.dng`.
The Samsung one is refused on purpose (JPEG-XL linear DNG). A DNG pulled off a phone running
this app (`adb exec-out run-as … cat files/captures/<id>/original.dng`) is the real test.

## Models

```bash
tool/models_in_docker.sh --check-image /src/build/check/person.jpg
```

Downloads into the `model-cache` volume, verifies sha256 pins, quantises SegFormer (MatMul +
Conv, uint8 weights → 28.6 MB), reports agreement with fp32, and writes
`app/src/main/assets/models/` (git-ignored). `--check-image` paths are container paths: the
repository is `/src`. A photo of a person is the useful check; `build/` is git-ignored.

## Build the APK

```bash
tool/build_in_docker.sh
```

Runs every unit test, assembles the debug APKs, fails if the merged manifest has INTERNET,
and copies to `build/docker/`:

- `garment-colour-debug-arm64-v8a.apk` — for the phone
- `garment-colour-debug-x86_64.apk` — for an emulator

Test reports land in `build/docker/reports/`. The first run fills the `gradle-home` volume
(several minutes); run it in the background and wait for the notification rather than
polling. **The build proves the Kotlin compiles — nothing about the camera, the models or the
screens on a phone.**

## Install on a phone

`adb` is not installed. One-time: `winget install Google.PlatformTools`. Then:

```bash
adb devices
adb install -r build/docker/garment-colour-debug-arm64-v8a.apk
adb shell monkey -p com.garmentcolour.app.debug -c android.intent.category.LAUNCHER 1
```

A local Docker build and a CI build are signed with different debug keys; switching between
them needs an uninstall, **which deletes every capture** (there is no backup). Ask first.

## Inspect what the app stored

Debug builds are debuggable, so `run-as` reads private files:

```bash
P=com.garmentcolour.app.debug
adb shell run-as $P ls files/captures
adb shell run-as $P ls -l files/captures/<id>
adb exec-out run-as $P cat files/captures/<id>/corrected.jpg > corrected.jpg
adb shell run-as $P sqlite3 databases/garment_colour.db "select id, tier, est_level, est_reason, applied_method from captures"
adb logcat -d | grep -iE "garmentcolour|Segmenters|AndroidRuntime" | tail -50
```

Settings in the app shows whether each model loaded, and why not.

## For an AI running this

- Run the spike checks and `tool/test_in_docker.sh` before calling anything done; say the
  number of tests and the worst parity ΔE.
- Change an algorithm on both sides, regenerate the fixtures, in one change.
- **Never describe device behaviour you have not observed**, and **never present synthetic
  accuracy as real accuracy**.
- Do not `adb uninstall` without asking.
