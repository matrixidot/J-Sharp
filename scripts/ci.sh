#!/usr/bin/env bash
# CI entry point: full build (unit + golden e2e tests under -Xverify:all) and formatting check.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon --console=plain spotlessCheck build
