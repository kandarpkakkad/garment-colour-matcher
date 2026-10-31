#!/usr/bin/env bash
# Downloads, verifies and quantises both models into app/src/main/assets/models/
# (D12, models LLD §2). Paths given to --check-image are inside the container:
# the repository is at /src.
#
#   tool/models_in_docker.sh
#   tool/models_in_docker.sh --check-image /src/build/person.jpg
#
# Time-limited: 30 minutes (the first run downloads about 130 MB). Exit 124
# when it fires.
set -euo pipefail
cd "$(dirname "$0")/.."
. tool/lib/docker_run.sh
run_service 30m models "$@"
