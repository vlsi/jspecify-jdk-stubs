#!/usr/bin/env bash
# Publishes the build/out/jspecify-jdk-stubs-jdk<N>.jar files, and the early-access
# jspecify-jdk-stubs-jdk<N>-ea.jar when build/out has one, as a GitHub release tagged vYYYY.MM.DD
# (UTC), or vYYYY.MM.DD.2 and so on when that tag already exists.
# Skips the release when the latest release has the same jar names with the same SHA-256 digests:
# the build is reproducible, so equal digests mean equal inputs. Without an early-access jar in
# build/out, the comparison leaves out the early-access jar of the latest release, so a failed
# early-access build alone does not publish a release.
#
# Uses gh with GH_TOKEN. EARLY_ACCESS_JDK names the JDK build the early-access jar comes from in the
# release notes. Set DRY_RUN=1 to print the release instead of creating it.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
# shellcheck source=SCRIPTDIR/../versions.env
source "$root/versions.env"

out_dir=$root/build/out
jars=("$out_dir"/jspecify-jdk-stubs-jdk*.jar)
if [[ ! -f "${jars[0]}" ]]; then
  echo "No jspecify-jdk-stubs-jdk*.jar in $out_dir; run ./gradlew stubs first" >&2
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
if [[ "$digests" != *-ea.jar\ * ]]; then
  latest_digests=$(grep -v -- '-ea\.jar ' <<< "$latest_digests" || true)
fi
latest_digests=$(sort <<< "$latest_digests")
if [[ "$latest_digests" == "$digests" ]]; then
  echo "The latest release already has these jars with the same SHA-256 digests; nothing to release"
  exit 0
fi

turbine_version=$(sed -n 's/^turbine = "com.google.turbine:turbine:\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")

early_access_note=
if [[ "$digests" == *-ea.jar\ * ]]; then
  early_access_note=" The early-access \`jspecify-jdk-stubs-jdk<N>-ea.jar\` declares the API of the next release as JDK ${EARLY_ACCESS_JDK:-<N>-ea} has it; that API may change before the release reaches GA."
fi

date_tag=v$(date -u +%Y.%m.%d)
tag=$date_tag
n=1
while gh release view "$tag" > /dev/null 2>&1; do
  n=$((n + 1))
  tag=$date_tag.$n
done
notes="JDK stubs with JSpecify annotations, built from [jspecify/jdk@${JSPECIFY_JDK_SHA:0:12}](https://github.com/jspecify/jdk/commit/$JSPECIFY_JDK_SHA) with Turbine $turbine_version. Each \`jspecify-jdk-stubs-jdk<N>.jar\` declares the API of Java SE release N, as \`javac --release N\` sees it.${early_access_note} The jars are licensed under the GNU General Public License, version 2, with the Classpath Exception, as OpenJDK is; each carries the license in \`META-INF/LICENSE\`.

SHA-256 digests, in the format \`shasum -a 256 --check\` reads:

\`\`\`text
$(sed -E 's/^([^ ]+) sha256:(.*)$/\2  \1/' <<< "$digests")
\`\`\`"

if [[ "${DRY_RUN:-}" == 1 ]]; then
  printf 'Would create release %s\n\nLatest release assets:\n%s\n\n%s\n' "$tag" "${latest_digests:-none}" "$notes"
  exit 0
fi
gh release create "$tag" "${jars[@]}" --title "$tag" --notes "$notes" ${GITHUB_SHA:+--target "$GITHUB_SHA"}
