#!/usr/bin/env bash
# Header-compiles build/jdk with Turbine into build/jspecify-jdk-headers.jar, then writes one
# build/out/jspecify-jdk-stubs-jdk<N>.jar per Java release that ReleaseStubs.java selects. Runs
# each step twice and checks that both runs wrote identical jars that pass HeaderCompileTest and
# ReleaseStubsTest.
#
# Runs on any OS after scripts/gensrc.sh. Uses $JAVA_HOME/bin/java, or java on PATH when
# JAVA_HOME is unset. That JDK provides the API of each release through its lib/ct.sym, so it has
# to be at least as new as the newest release in GA; ReleaseStubs.java names the JDK it needs
# when it is not.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=SCRIPTDIR/../versions.env
source "$root/versions.env"

work=${WORK_DIR:-$root/build}
jdk_dir=$work/jdk
out_dir=$work/out
turbine_version=${TURBINE_TAG#v}
turbine_jar=$work/turbine-$turbine_version.jar
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
mkdir -p "$work"

if [[ ! -f "$turbine_jar" ]]; then
  echo "Downloading Turbine $turbine_version"
  curl --fail --silent --show-error --location \
    "https://github.com/google/turbine/releases/download/$TURBINE_TAG/turbine-$turbine_version-all-deps.jar" \
    --output "$turbine_jar.tmp"
  echo "$TURBINE_SHA256  $turbine_jar.tmp" | shasum -a 256 --check --quiet
  mv "$turbine_jar.tmp" "$turbine_jar"
fi

headers=$work/jspecify-jdk-headers.jar
"$java" "$root/src/HeaderCompile.java" --turbine "$turbine_jar" --jdk-src "$jdk_dir" --output "$headers"
"$java" "$root/src/HeaderCompile.java" --turbine "$turbine_jar" --jdk-src "$jdk_dir" --output "$work/second-run.jar"
"$java" "$root/src/HeaderCompileTest.java" "$headers" "$work/second-run.jar"
rm "$work/second-run.jar"

rm -rf "$out_dir" "$work/second-run"
"$java" "$root/src/ReleaseStubs.java" --headers "$headers" --output-dir "$out_dir"
"$java" "$root/src/ReleaseStubs.java" --headers "$headers" --output-dir "$work/second-run" > /dev/null
"$java" "$root/src/ReleaseStubsTest.java" "$out_dir" "$work/second-run"
rm -r "$work/second-run"
