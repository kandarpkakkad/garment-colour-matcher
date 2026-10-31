#!/usr/bin/env bash
# The Python reference, in Docker. Accuracy LLD §1.
#
#   tool/spike_in_docker.sh test          self-checks (default)
#   tool/spike_in_docker.sh fixtures      rewrite color-engine's test fixtures
#   tool/spike_in_docker.sh bench [args]  the phase 0 benchmark
#
# Time-limited: 20 minutes, GCM_TIMEOUT to change it. Exit 124 when it fires.
set -euo pipefail
cd "$(dirname "$0")/.."
. tool/lib/docker_run.sh
run_service 20m spike "${@:-test}"
