# jspecify-jdk-stubs

Prebuilt stubs of the JDK API with the JSpecify nullness annotations from
[jspecify/jdk](https://github.com/jspecify/jdk). The jar holds header-only class files, compiled by
[Turbine](https://github.com/google/turbine), for the classes that carry at least one JSpecify annotation. It has no
method bodies and no JSpecify annotation classes. The idea comes from
[jspecify/jdk#178](https://github.com/jspecify/jdk/issues/178).

Download `jspecify-jdk-stubs.jar` from the [releases](https://github.com/vlsi/jspecify-jdk-stubs/releases). This is
not an official JSpecify release.

## How releases are made

[versions.env](versions.env) pins the jspecify/jdk commit and the Turbine release with the SHA-256 of its jar, and
Renovate opens pull requests that move both. On the first day of each month, the
[Build](.github/workflows/build.yml) workflow builds the jar and publishes it as a release tagged `vYYYY.MM.DD`, or
`vYYYY.MM.DD.2` and so on for a later release on the same day. The build is reproducible, so the workflow skips the
release when the jar has the same SHA-256 as the one in the latest release.

## Building locally

The build has two steps:

1. `scripts/gensrc.sh` fetches jspecify/jdk at the pinned commit into `build/jdk`, runs `configure`, and runs
   `make gensrc`. It runs on Linux only, because `configure` on macOS needs a full Xcode with the Metal toolchain. It
   is skipped when `build/jdk` already holds the output for the pinned commit.
2. `scripts/stubs.sh` downloads Turbine, compiles `build/out/jspecify-jdk-stubs.jar` twice, and checks that both jars
   are identical and pass `src/HeaderCompileTest.java`. It runs on any OS with JDK 24 or later.

On macOS, run the first step in Docker:

```bash
scripts/gensrc-in-docker.sh
```

```bash
JAVA_HOME=/path/to/jdk-26 scripts/stubs.sh
```

On Linux with JDK 26 and the packages listed in [docker/gensrc.Dockerfile](docker/gensrc.Dockerfile),
`scripts/build.sh` runs both steps.

`src/HeaderCompile.java` and `src/HeaderCompileTest.java` are copied from jspecify/jdk commit
[9e93802](https://github.com/jspecify/jdk/commit/9e93802934bc59c6924cbacf1855293d90f799ad), because the jspecify/jdk
main branch does not have them yet.
