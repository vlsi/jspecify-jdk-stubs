#!/usr/bin/env bash
# Header-compiles build/jdk with Turbine into build/out/jspecify-jdk-stubs.jar, compiles it a
# second time, and checks that both jars are identical and pass HeaderCompileTest.
#
# Runs on any OS after scripts/gensrc.sh. Uses $JAVA_HOME/bin/java, or java on PATH when
# JAVA_HOME is unset; HeaderCompileTest needs JDK 24 or later.
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
mkdir -p "$out_dir"

if [[ ! -f "$turbine_jar" ]]; then
  echo "Downloading Turbine $turbine_version"
  curl --fail --silent --show-error --location \
    "https://github.com/google/turbine/releases/download/$TURBINE_TAG/turbine-$turbine_version-all-deps.jar" \
    --output "$turbine_jar.tmp"
  echo "$TURBINE_SHA256  $turbine_jar.tmp" | shasum -a 256 --check --quiet
  mv "$turbine_jar.tmp" "$turbine_jar"
fi

stubs=$out_dir/jspecify-jdk-stubs.jar
"$java" "$root/src/HeaderCompile.java" --turbine "$turbine_jar" --jdk-src "$jdk_dir" --output "$stubs"
"$java" "$root/src/HeaderCompile.java" --turbine "$turbine_jar" --jdk-src "$jdk_dir" --output "$work/second-run.jar"
"$java" "$root/src/HeaderCompileTest.java" "$stubs" "$work/second-run.jar"
rm "$work/second-run.jar"
