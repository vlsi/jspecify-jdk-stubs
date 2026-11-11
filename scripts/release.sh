#!/usr/bin/env bash
# Publishes the build/out/jspecify-jdk-stubs-jdk<N>.jar files as a GitHub release tagged
# vYYYY.MM.DD (UTC), or vYYYY.MM.DD.2 and so on when that tag already exists.
# Skips the release when the latest release has the same jar names with the same SHA-256 digests:
# the build is reproducible, so equal digests mean equal inputs.
#
# Uses gh with GH_TOKEN. Set DRY_RUN=1 to print the release instead of creating it.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=SCRIPTDIR/../versions.env
source "$root/versions.env"

out_dir=${WORK_DIR:-$root/build}/out
jars=("$out_dir"/jspecify-jdk-stubs-jdk*.jar)
if [[ ! -f "${jars[0]}" ]]; then
  echo "No jspecify-jdk-stubs-jdk*.jar in $out_dir; run scripts/build.sh first" >&2
  exit 1
fi

# One "<name> sha256:<digest>" line per jar, sorted by name, in the form gh reports assets.
digests=$(cd "$out_dir" && shasum -a 256 jspecify-jdk-stubs-jdk*.jar | awk '{print $2 " sha256:" $1}' | sort)

if ! latest_digests=$(gh release view --json assets \
  --jq '.assets[] | "\(.name) \(.digest)"' 2>"${TMPDIR:-/tmp}/gh-release.err"); then
  # gh prints exactly this when the repository has no release yet.
  if [[ "$(cat "${TMPDIR:-/tmp}/gh-release.err")" != "release not found" ]]; then
    cat "${TMPDIR:-/tmp}/gh-release.err" >&2
    exit 1
  fi
  latest_digests=
fi
latest_digests=$(sort <<< "$latest_digests")
if [[ "$latest_digests" == "$digests" ]]; then
  echo "The latest release already has these jars with the same SHA-256 digests; nothing to release"
  exit 0
fi

date_tag=v$(date -u +%Y.%m.%d)
tag=$date_tag
n=1
while gh release view "$tag" > /dev/null 2>&1; do
  n=$((n + 1))
  tag=$date_tag.$n
done
notes="JDK stubs with JSpecify annotations, built from [jspecify/jdk@${JSPECIFY_JDK_SHA:0:12}](https://github.com/jspecify/jdk/commit/$JSPECIFY_JDK_SHA) with Turbine ${TURBINE_TAG#v}. Each \`jspecify-jdk-stubs-jdk<N>.jar\` declares the API of Java SE release N, as \`javac --release N\` sees it.

SHA-256 digests, in the format \`shasum -a 256 --check\` reads:

\`\`\`text
$(sed -E 's/^([^ ]+) sha256:(.*)$/\2  \1/' <<< "$digests")
\`\`\`"

if [[ "${DRY_RUN:-}" == 1 ]]; then
  printf 'Would create release %s\n\nLatest release assets:\n%s\n\n%s\n' "$tag" "${latest_digests:-none}" "$notes"
  exit 0
fi
gh release create "$tag" "${jars[@]}" --title "$tag" --notes "$notes" ${GITHUB_SHA:+--target "$GITHUB_SHA"}
