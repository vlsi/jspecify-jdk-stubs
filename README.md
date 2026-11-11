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
first day after the GA month of a new release, the build fails until `jdkBuildVersion` in
[gradle.properties](gradle.properties) names that JDK, and the error names it.

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
`-XepOpt:NullAway:JSpecifyMode=true` and `-XDaddTypeAnnotationsToSymbol=true`. javac of JDK 21 accepts the second
flag from 21.0.8 in OpenJDK builds such as Temurin, and not in Oracle JDK; see
[NullAway's supported JDK versions](https://github.com/uber/NullAway/wiki/JSpecify-Support#supported-jdk-versions).

## How releases are made

[versions.env](versions.env) pins the jspecify/jdk commit, [gradle/libs.versions.toml](gradle/libs.versions.toml) pins
Turbine, Error Prone, and NullAway, and [gradle/verification-metadata.xml](gradle/verification-metadata.xml) lists the
PGP keys that every jar the build downloads has to be signed with, with
[gradle/verification-keyring.keys](gradle/verification-keyring.keys) holding the keys; a jar that is unsigned or whose
key no key server has is pinned by its SHA-256 instead. `jdkBuildVersion` in [gradle.properties](gradle.properties)
names the JDK that builds the jars, the newest release in GA, and
[gradle/gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties) the JDK that runs Gradle, the newest LTS
release. Renovate opens pull requests that move all of them. On the first day of each month, the
[Build](.github/workflows/build.yml) workflow builds the jars and publishes them as a release tagged `vYYYY.MM.DD`, or
`vYYYY.MM.DD.2` and so on for a later release on the same day. The build is reproducible, so the workflow skips the
release when the latest release has the same jars with the same SHA-256 digests.

Before the release, the workflow compiles code that calls the JDK with NullAway against each jar, put in front of the
platform classes as [Which jar to use](#which-jar-to-use) shows. It does that with javac of every LTS release from 21
and of the build JDK, and each javac checks the jars of the releases it can compile for.

## Building locally

```bash
./gradlew stubs
```

writes `build/out/jspecify-jdk-stubs-jdk<N>.jar`. Gradle runs on the JDK that `gradle/gradle-daemon-jvm.properties`
names and has to find it installed; it builds the jars with the JDK that `jdkBuildVersion` names, which it downloads
when none is installed. The build has two steps:

1. `gensrc` fetches jspecify/jdk at the pinned commit into `build/jdk`, runs `configure`, and runs `make gensrc`. The
   JDK `configure` script needs a full Xcode with the Metal toolchain on macOS, so on every OS but Linux the step runs
   in Docker through `scripts/gensrc-in-docker.sh`; `-Pgensrc=local` or `-Pgensrc=docker` overrides that. On Linux it
   needs the packages listed in [docker/gensrc.Dockerfile](docker/gensrc.Dockerfile). It is skipped when `build/jdk`
   already holds the output for the pinned commit, and `./gradlew clean` keeps that output.
2. `HeaderCompile` compiles `build/jspecify-jdk-headers.jar` with Turbine. Then `ReleaseStubs` writes a jar for each
   selected release, taking the API of release `<N>` from the `lib/ct.sym` of the build JDK. Each step runs twice, and
   `HeaderCompileTest` and `ReleaseStubsTest` check that both runs wrote identical jars.

```bash
./gradlew check
```

also runs `stubsTest`, the NullAway check, with javac of the build JDK. `-PjdkTestVersion=21` runs it with another
javac, and `./gradlew stubsTest -PstubsDir=<dir>` checks the jars in `<dir>` instead of building them.

`src/main/java/HeaderCompile.java` and `src/main/java/HeaderCompileTest.java` are copied from jspecify/jdk commit
[9e93802](https://github.com/jspecify/jdk/commit/9e93802934bc59c6924cbacf1855293d90f799ad), because the jspecify/jdk
main branch does not have them yet. Here, `--turbine` of `HeaderCompile.java` takes a class path rather than one jar, so
Turbine comes with its dependencies as separate jars.
