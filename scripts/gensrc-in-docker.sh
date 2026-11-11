#!/usr/bin/env bash
# Runs scripts/gensrc.sh in a Linux container, so it works on macOS too. The output lands in
# build/jdk on the host. ./gradlew gensrc runs it everywhere but on Linux.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
image=jspecify-jdk-stubs-gensrc

docker build --quiet --tag "$image" --file "$root/docker/gensrc.Dockerfile" "$root/docker" > /dev/null
docker run --rm -v "$root:/repo" -w /repo "$image" scripts/gensrc.sh
