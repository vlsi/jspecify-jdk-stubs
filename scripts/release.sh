#!/usr/bin/env bash
# Publishes build/out/jspecify-jdk-stubs.jar as a GitHub release tagged vYYYY.MM.DD (UTC),
# or vYYYY.MM.DD.2 and so on when that tag already exists.
# Skips the release when the latest release already has a jar with the same SHA-256: the build
# is reproducible, so an equal digest means equal inputs.
#
# Uses gh with GH_TOKEN. Set DRY_RUN=1 to print the release instead of creating it.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=SCRIPTDIR/../versions.env
source "$root/versions.env"

jar_name=jspecify-jdk-stubs.jar
jar=${WORK_DIR:-$root/build}/out/$jar_name
digest=$(shasum -a 256 "$jar" | cut -d' ' -f1)

if ! latest_digest=$(gh release view --json assets \
  --jq ".assets[] | select(.name == \"$jar_name\") | .digest" 2>"${TMPDIR:-/tmp}/gh-release.err"); then
  # gh prints exactly this when the repository has no release yet.
  if [[ "$(cat "${TMPDIR:-/tmp}/gh-release.err")" != "release not found" ]]; then
    cat "${TMPDIR:-/tmp}/gh-release.err" >&2
    exit 1
  fi
  latest_digest=
fi
if [[ "$latest_digest" == "sha256:$digest" ]]; then
  echo "The latest release already has $jar_name with SHA-256 $digest; nothing to release"
  exit 0
fi

date_tag=v$(date -u +%Y.%m.%d)
tag=$date_tag
n=1
while gh release view "$tag" > /dev/null 2>&1; do
  n=$((n + 1))
  tag=$date_tag.$n
done
notes="JDK stubs with JSpecify annotations, built from [jspecify/jdk@${JSPECIFY_JDK_SHA:0:12}](https://github.com/jspecify/jdk/commit/$JSPECIFY_JDK_SHA) with Turbine ${TURBINE_TAG#v}.

SHA-256 of \`$jar_name\`: \`$digest\`"

if [[ "${DRY_RUN:-}" == 1 ]]; then
  printf 'Would create release %s (latest digest: %s)\n\n%s\n' "$tag" "${latest_digest:-none}" "$notes"
  exit 0
fi
gh release create "$tag" "$jar" --title "$tag" --notes "$notes" ${GITHUB_SHA:+--target "$GITHUB_SHA"}
