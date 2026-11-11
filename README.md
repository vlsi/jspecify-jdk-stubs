# jspecify-jdk-stubs

Prebuilt stubs of the JDK API with the JSpecify nullness annotations from
[jspecify/jdk](https://github.com/jspecify/jdk). Each release has one jar per Java SE release:
`jspecify-jdk-stubs-jdk<N>.jar` holds header-only class files of the classes that carry at least one JSpecify
annotation, with exactly the API that `javac --release <N>` sees. The jars have no method bodies and no JSpecify
annotation classes. The idea comes from [jspecify/jdk#178](https://github.com/jspecify/jdk/issues/178).

Download the jars from the [releases](https://github.com/vlsi/jspecify-jdk-stubs/releases). This is not an official
JSpecify release.

## Which jar to use

Take the jar whose `<N>` matches the `--release` (or `-source`/`-target`) you compile with. A release gets a jar when it
is an LTS release (8, 11, 17, 21, 25, and every fourth release after that), and every other release gets one in the
twelve months after its GA month: JDK 26, released in March 2026, gets a jar from April 2026 to March 2027. From the
first day after the GA month of a new release, the build fails until it runs on that JDK, and the error names it.

The annotations come from the newest jspecify/jdk, and the API comes from the `lib/ct.sym` file of the JDK that runs the
build. A method or constructor that release `<N>` has and jspecify/jdk does not, such as `Thread.stop(Throwable)` in
release 8, carries `@NullUnmarked`, so a checker treats it as unannotated.

Put the jar in front of the platform classes, and the `org.jspecify:jspecify` jar on the classpath:

```bash
# --release 9 or later; the jar replaces classes of java.base
javac --release 21 --patch-module java.base=jspecify-jdk-stubs-jdk21.jar ...
```

```bash
# release 8, where javac rejects --patch-module; rt.jar comes from a JDK 8
javac -source 8 -target 8 -bootclasspath jspecify-jdk-stubs-jdk8.jar:/path/to/jdk-8/jre/lib/rt.jar ...
```

`--patch-module java.base` covers the classes of `java.base` only. NullAway reads the annotations when it runs with
`-XepOpt:NullAway:JSpecifyMode=true` and `-XDaddTypeAnnotationsToSymbol=true`.

## How releases are made

[versions.env](versions.env) pins the jspecify/jdk commit and the Turbine release with the SHA-256 of its jar, and
Renovate opens pull requests that move both. On the first day of each month, the
[Build](.github/workflows/build.yml) workflow builds the jars and publishes them as a release tagged `vYYYY.MM.DD`, or
`vYYYY.MM.DD.2` and so on for a later release on the same day. The build is reproducible, so the workflow skips the
release when the latest release has the same jars with the same SHA-256 digests.

## Building locally

The build has two steps:

1. `scripts/gensrc.sh` fetches jspecify/jdk at the pinned commit into `build/jdk`, runs `configure`, and runs
   `make gensrc`. It runs on Linux only, because `configure` on macOS needs a full Xcode with the Metal toolchain. It
   is skipped when `build/jdk` already holds the output for the pinned commit.
2. `scripts/stubs.sh` downloads Turbine and compiles `build/jspecify-jdk-headers.jar`. Then `src/ReleaseStubs.java`
   writes `build/out/jspecify-jdk-stubs-jdk<N>.jar` for each selected release, taking the API of release `<N>` from the
   `lib/ct.sym` of the JDK that runs it. Each step runs twice, and `src/HeaderCompileTest.java` and
   `src/ReleaseStubsTest.java` check that both runs wrote identical jars. It runs on any OS with a JDK at least as new
   as the newest release in GA; with an older JDK, it fails and names the JDK it needs.

On macOS, run the first step in Docker:

```bash
scripts/gensrc-in-docker.sh
```

```bash
JAVA_HOME=/path/to/jdk-27 scripts/stubs.sh
```

On Linux with JDK 27 and the packages listed in [docker/gensrc.Dockerfile](docker/gensrc.Dockerfile),
`scripts/build.sh` runs both steps.

`src/HeaderCompile.java` and `src/HeaderCompileTest.java` are copied from jspecify/jdk commit
[9e93802](https://github.com/jspecify/jdk/commit/9e93802934bc59c6924cbacf1855293d90f799ad), because the jspecify/jdk
main branch does not have them yet.
