# Versioning — LLD

## One file

`version.properties` at the root holds the only copy:

```
versionName=0.1.0
```

`app/build.gradle.kts` reads it and derives

```
versionCode = major · 1 000 000 + minor · 1 000 + patch
```

so the code only goes up when the version does, and the two can never drift: there is no
second place to forget. The app shows `BuildConfig.VERSION_NAME`.

## Moving it

`tool/bump_version.sh patch|minor|major|set X.Y.Z` rewrites the file, checks the rewrite
landed, and prints the new version. CI calls it (release pipeline); never edit by hand.

Limits the scheme imposes: minor and patch below 1000.

## Engine version

`Engine.VERSION` is separate and moves only when the same photo would produce different
numbers (pipeline LLD §1). It is stored per capture.
