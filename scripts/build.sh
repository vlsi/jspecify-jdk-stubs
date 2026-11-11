#!/usr/bin/env bash
# Builds build/out/jspecify-jdk-stubs-jdk<N>.jar on Linux: scripts/gensrc.sh, then scripts/stubs.sh.
set -euo pipefail

scripts=$(dirname "$0")
"$scripts/gensrc.sh"
"$scripts/stubs.sh"
