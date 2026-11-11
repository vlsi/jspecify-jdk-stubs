#!/usr/bin/env bash
# Fetches jspecify/jdk at the commit pinned in versions.env into build/jdk and runs
# `make gensrc` there. Skips both when build/jdk already holds gensrc output for that commit.
#
# Runs on Linux: the JDK configure script needs a full Xcode with the Metal toolchain on macOS.
# ./gradlew gensrc runs it on Linux with the build JDK on PATH, and through
# scripts/gensrc-in-docker.sh elsewhere.
#
# Requires a JDK 26 or later on PATH (configure uses it as the boot JDK), git, autoconf, make,
# a C/C++ compiler, and the headers for cups, alsa, and fontconfig.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=SCRIPTDIR/../versions.env
source "$root/versions.env"

work=${WORK_DIR:-$root/build}
jdk_dir=$work/jdk
stamp=$work/gensrc.sha
mkdir -p "$work"

if [[ "$(cat "$stamp" 2>/dev/null || true)" == "$JSPECIFY_JDK_SHA" ]]; then
  echo "gensrc for $JSPECIFY_JDK_SHA is up to date"
  exit 0
fi
rm -f "$stamp"

if [[ "$(git -C "$jdk_dir" rev-parse HEAD 2>/dev/null || true)" != "$JSPECIFY_JDK_SHA" ]]; then
  echo "Fetching jspecify/jdk at $JSPECIFY_JDK_SHA"
  rm -rf "$jdk_dir"
  git init -q "$jdk_dir"
  git -C "$jdk_dir" fetch -q --depth 1 https://github.com/jspecify/jdk.git "$JSPECIFY_JDK_SHA"
  git -C "$jdk_dir" -c advice.detachedHead=false checkout -q FETCH_HEAD
fi

echo "Generating JDK sources"
cd "$jdk_dir"
bash ./configure --enable-headless-only --disable-warnings-as-errors > "$work/configure.log" 2>&1 \
  || { tail -n 30 "$work/configure.log"; exit 1; }
make gensrc > "$work/gensrc.log" 2>&1 \
  || { tail -n 40 "$work/gensrc.log"; exit 1; }
echo "$JSPECIFY_JDK_SHA" > "$stamp"
