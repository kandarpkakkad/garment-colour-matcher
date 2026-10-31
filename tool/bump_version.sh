#!/usr/bin/env bash
# Moves the version in version.properties, the only place it lives
# (docs/01-platform/versioning/lld.md). The versionCode is derived from it.
#
#   tool/bump_version.sh patch       0.1.1 -> 0.1.2
#   tool/bump_version.sh minor       0.1.1 -> 0.2.0
#   tool/bump_version.sh major       0.1.1 -> 1.0.0
#   tool/bump_version.sh set 0.3.0   -> 0.3.0
#
# Prints the new version to stdout.
set -euo pipefail
cd "$(dirname "$0")/.."

FILE=version.properties
level="${1:?usage: bump_version.sh <patch|minor|major|set> [version]}"
current="$(grep -E '^versionName=' "$FILE" | head -1 | cut -d= -f2 | tr -d '[:space:]')"
IFS=. read -r major minor patch <<<"$current"

case "$level" in
  patch) patch=$((patch + 1)) ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  major) major=$((major + 1)); minor=0; patch=0 ;;
  set)
    next="${2:?set needs a version, e.g. 0.3.0}"
    next="${next#v}"     # a tag is fine too
    IFS=. read -r major minor patch <<<"$next"
    ;;
  *) echo "unknown level: $level" >&2; exit 1 ;;
esac

for part in "$major" "$minor" "$patch"; do
  [[ "$part" =~ ^[0-9]+$ ]] || { echo "not a version: $major.$minor.$patch" >&2; exit 1; }
done
# versionCode = major·1 000 000 + minor·1 000 + patch: minor and patch must stay below 1000.
[ "$minor" -lt 1000 ] && [ "$patch" -lt 1000 ] || { echo "minor and patch must be below 1000" >&2; exit 1; }

version="$major.$minor.$patch"
printf 'versionName=%s\n' "$version" > "$FILE"
grep -qx "versionName=$version" "$FILE" || { echo "$FILE did not take the new version" >&2; exit 1; }
echo "$version"
