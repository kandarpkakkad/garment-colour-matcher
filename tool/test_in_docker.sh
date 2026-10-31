#!/usr/bin/env bash
# The JVM unit tests, in Docker. Reports land in build/docker/reports/.
# Includes FixtureParityTest, the phase 2 gate.
#
#   tool/test_in_docker.sh           engine + app unit tests
#   tool/test_in_docker.sh engine    the engine alone — the fast loop for colour work
#   tool/test_in_docker.sh dng       real DNGs in build/check/dng/ through the RAW path
#   GCM_TIMEOUT=60m tool/test_in_docker.sh
#
# Time-limited: 30 minutes (the first run fills the Gradle cache). Exit 124
# when it fires.
set -euo pipefail
cd "$(dirname "$0")/.."
. tool/lib/docker_run.sh
run_service 30m gradle "${1:-test}"
