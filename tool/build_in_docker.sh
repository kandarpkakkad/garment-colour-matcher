#!/usr/bin/env bash
# Every unit test, then the debug APKs, in Docker. The APKs land in
# build/docker/; the merged manifest is checked for the INTERNET permission (D3).
#
#   tool/build_in_docker.sh
#   GCM_TIMEOUT=90m tool/build_in_docker.sh
#
# Run tool/models_in_docker.sh first, or the APK builds without its models and
# runs at tier 3 (D20). Time-limited: 60 minutes, because the first run downloads
# Gradle and the Android dependencies. Exit 124 when it fires.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -f app/src/main/assets/models/segformer_b2_clothes_int8.onnx ] \
   || [ ! -f app/src/main/assets/models/selfie_multiclass_256x256.tflite ]; then
  echo "tool/build_in_docker.sh: models missing — building without them (run tool/models_in_docker.sh)" >&2
fi
. tool/lib/docker_run.sh
run_service 60m gradle build
