import java.time.LocalDate
import java.time.ZoneOffset

plugins {
    java
    `jvm-test-suite`
}

val jdkBuildVersion = providers.gradleProperty("jdkBuildVersion").map { JavaLanguageVersion.of(it) }
val jdkTestVersion = providers.gradleProperty("jdkTestVersion").map { JavaLanguageVersion.of(it) }
    .orElse(jdkBuildVersion)
// The next Java release, still in development: its early-access jar comes from an early-access
// build of it, so it moves on when Renovate moves jdkBuildVersion at the GA of the next release.
val jdkEarlyAccessVersion = jdkBuildVersion.map { JavaLanguageVersion.of(it.asInt() + 1) }
// Inside a TaskContainer extension, javaToolchains names the task that lists the toolchains.
val toolchains: JavaToolchainService = javaToolchains

java {
    toolchain.languageVersion = jdkBuildVersion
}

val turbine = configurations.dependencyScope("turbine")
val turbineClasspath = configurations.resolvable("turbineClasspath") {
    extendsFrom(turbine.get())
}

dependencies {
    turbine(libs.turbine)
}

// scripts/gensrc.sh writes the jspecify/jdk sources there and the commit they come from to
// gensrc.sha, which stands for the whole tree as a task input.
val jdkSrc = layout.buildDirectory.dir("jdk")
val gensrcStamp = layout.buildDirectory.file("gensrc.sha")

/** The current UTC date, which selects the releases; the configuration cache rechecks it on every load. */
abstract class UtcDate : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = LocalDate.now(ZoneOffset.UTC).toString()
}

val today = providers.of(UtcDate::class) {}

tasks.clean {
    // The sources take a few minutes of configure and make to rebuild.
    val buildDir = layout.buildDirectory.get().asFile
    val gensrcOutputs = setOf("jdk", "gensrc.sha", "configure.log", "gensrc.log")
    delete = setOf(provider { buildDir.listFiles { file -> file.name !in gensrcOutputs }.orEmpty().toList() })
}

// The JDK configure script needs a full Xcode with the Metal toolchain on macOS, so gensrc runs in
// a Linux container everywhere but on Linux. -Pgensrc=local or -Pgensrc=docker overrides that.
val gensrcInDocker = providers.gradleProperty("gensrc")
    .map {
        when (it) {
            "local" -> false
            "docker" -> true
            else -> throw GradleException("-Pgensrc must be local or docker, got $it")
        }
    }
    .orElse(providers.systemProperty("os.name").map { it != "Linux" })
val buildJdkHome = javaToolchains.launcherFor { languageVersion = jdkBuildVersion }
    .map { it.metadata.installationPath.asFile.path }

val gensrc by tasks.registering(Exec::class) {
    description = "Fetches jspecify/jdk at the commit in versions.env and runs make gensrc in build/jdk."
    // scripts/gensrc.sh skips the work when gensrc.sha names the commit in versions.env. Gradle
    // would delete a declared output on a run with no history, so the stamp is not one.
    val inDocker = gensrcInDocker
    val jdkHome = buildJdkHome
    doFirst {
        if (inDocker.get()) {
            commandLine("scripts/gensrc-in-docker.sh")
        } else {
            // configure takes the JDK on PATH as the boot JDK.
            commandLine("scripts/gensrc.sh")
            environment("JAVA_HOME", jdkHome.get())
            environment("PATH", "${jdkHome.get()}/bin${File.pathSeparator}${System.getenv("PATH")}")
        }
    }
}

// The task actions reference only local values, which the configuration cache can store.
fun TaskContainer.registerHeaderCompile(name: String, output: Provider<RegularFile>) =
    register<JavaExec>(name) {
        description = "Header-compiles the jspecify/jdk sources with Turbine into ${output.get().asFile.name}."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "HeaderCompile"
        dependsOn(gensrc)
        inputs.file(gensrcStamp)
        val turbineJars: FileCollection = turbineClasspath.get()
        inputs.files(turbineJars).withNormalizer(ClasspathNormalizer::class)
        outputs.file(output)
        val jdkSrcDir = jdkSrc.get().asFile
        argumentProviders.add {
            listOf("--turbine", turbineJars.asPath, "--jdk-src", jdkSrcDir.path, "--output", output.get().asFile.path)
        }
    }

fun TaskContainer.registerReleaseStubs(
    name: String,
    headers: FileCollection,
    outputDir: Provider<Directory>,
    earlyAccess: Boolean = false,
) =
    register<JavaExec>(name) {
        description = if (earlyAccess) {
            "Writes the early-access stubs jar of the release of the early-access JDK into ${outputDir.get().asFile.name}."
        } else {
            "Writes one stubs jar per selected Java release into ${outputDir.get().asFile.name}."
        }
        if (earlyAccess) {
            // The API comes from the lib/ct.sym of the JDK that runs ReleaseStubs.
            javaLauncher = toolchains.launcherFor { languageVersion = jdkEarlyAccessVersion }
        }
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "ReleaseStubs"
        inputs.files(headers)
        // A jspecify/jdk commit can change the license files the jars carry and leave the headers jar as it was.
        inputs.file(gensrcStamp)
        inputs.property("today", today)
        outputs.dir(outputDir)
        val date = today
        val jdkSrcDir = jdkSrc.get().asFile
        doFirst { outputDir.get().asFile.deleteRecursively() }
        argumentProviders.add {
            listOf(
                "--headers", headers.singleFile.path,
                "--legal-dir", jdkSrcDir.path,
                "--output-dir", outputDir.get().asFile.path,
                "--today", date.get(),
            ) + if (earlyAccess) listOf("--early-access") else listOf()
        }
    }

fun TaskContainer.registerReproducibilityCheck(
    name: String,
    mainClass: String,
    first: TaskProvider<*>,
    second: TaskProvider<*>,
    vararg extraArgs: Provider<String>,
    javaVersion: Provider<JavaLanguageVersion>? = null,
) =
    register<JavaExec>(name) {
        if (javaVersion != null) {
            javaLauncher = toolchains.launcherFor { languageVersion = javaVersion }
        }
        classpath = sourceSets.main.get().runtimeClasspath
        this.mainClass = mainClass
        val firstOutput = files(first)
        val secondOutput = files(second)
        inputs.files(firstOutput, secondOutput)
        val extra = extraArgs.toList()
        argumentProviders.add {
            listOf(firstOutput.singleFile.path, secondOutput.singleFile.path) + extra.map { it.get() }
        }
    }

// Each step runs twice, and the *Test programs check that both runs wrote identical jars.
val headerCompile = tasks.registerHeaderCompile("headerCompile", layout.buildDirectory.file("jspecify-jdk-headers.jar"))
val headerCompileRerun = tasks.registerHeaderCompile(
    "headerCompileRerun", layout.buildDirectory.file("rerun/jspecify-jdk-headers.jar"),
)
val releaseStubs = tasks.registerReleaseStubs("releaseStubs", files(headerCompile), layout.buildDirectory.dir("out"))
val releaseStubsRerun = tasks.registerReleaseStubs(
    "releaseStubsRerun", files(headerCompile), layout.buildDirectory.dir("rerun/out"),
)

val checkHeaders = tasks.registerReproducibilityCheck("checkHeaders", "HeaderCompileTest", headerCompile, headerCompileRerun)
checkHeaders { description = "Checks the headers jar and that a second Turbine run wrote the same bytes." }
val checkReleaseStubs = tasks.registerReproducibilityCheck(
    "checkReleaseStubs", "ReleaseStubsTest", releaseStubs, releaseStubsRerun, today, jdkSrc.map { it.asFile.path },
)
checkReleaseStubs { description = "Checks the stubs jars and that a second run wrote the same bytes." }

// The early-access jar takes its API from the ct.sym of the early-access JDK. The jars of the
// releases in GA take theirs from the build JDK only, so an early-access build cannot change them.
val releaseStubsEarlyAccess = tasks.registerReleaseStubs(
    "releaseStubsEarlyAccess", files(headerCompile), layout.buildDirectory.dir("early-access"), earlyAccess = true,
)
val releaseStubsEarlyAccessRerun = tasks.registerReleaseStubs(
    "releaseStubsEarlyAccessRerun", files(headerCompile), layout.buildDirectory.dir("rerun/early-access"),
    earlyAccess = true,
)
val checkReleaseStubsEarlyAccess = tasks.registerReproducibilityCheck(
    "checkReleaseStubsEarlyAccess", "ReleaseStubsTest", releaseStubsEarlyAccess, releaseStubsEarlyAccessRerun,
    today, jdkSrc.map { it.asFile.path }, provider { "--early-access" },
    javaVersion = jdkEarlyAccessVersion,
)
checkReleaseStubsEarlyAccess {
    description = "Checks the early-access stubs jar and that a second run wrote the same bytes."
}

tasks.register("stubs") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds build/out/jspecify-jdk-stubs-jdk<N>.jar and checks that the build is reproducible."
    dependsOn(releaseStubs, checkHeaders, checkReleaseStubs)
}

// Not part of check: it needs an early-access JDK, which Gradle cannot download, and a problem with
// the early-access build must not hold back the jars of the releases in GA.
tasks.register("earlyAccessStubs") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds build/early-access/jspecify-jdk-stubs-jdk<N>-ea.jar, where N is jdkBuildVersion + 1," +
        " and checks that the build is reproducible."
    dependsOn(releaseStubsEarlyAccess, checkHeaders, checkReleaseStubsEarlyAccess)
}

// -PstubsDir tests the jars of an earlier build, such as the CI artifact, instead of building them.
val stubsDir = providers.gradleProperty("stubsDir").orNull.let { if (it != null) files(it) else files(releaseStubs) }

testing {
    suites {
        register<JvmTestSuite>("stubsTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(libs.errorprone.core)
                implementation(libs.jspecify)
                implementation(libs.nullaway)
            }
            targets.configureEach {
                testTask.configure {
                    description = "Compiles code that calls the JDK with NullAway over each stubs jar."
                    checkStubs(stubsDir, jdkTestVersion)
                }
            }
        }
    }
}

/** Runs StubsNullAwayTest over the jars in [stubsJars] with javac of [jdk]. */
fun Test.checkStubs(stubsJars: FileCollection, jdk: Provider<JavaLanguageVersion>) {
    javaLauncher = javaToolchains.launcherFor { languageVersion = jdk }
    inputs.files(stubsJars).withPathSensitivity(PathSensitivity.NAME_ONLY)
    jvmArgumentProviders.add { listOf("-DstubsDir=${stubsJars.singleFile}") }
    // Error Prone runs inside javac and reaches its internals.
    jvmArgs(
        listOf("api", "code", "comp", "file", "main", "model", "parser", "processing", "tree", "util")
            .map { "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" },
    )
    jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
}

val stubsTestSourceSet = sourceSets.named("stubsTest")

tasks.register<Test>("earlyAccessStubsTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Compiles code that calls the JDK with NullAway over the early-access stubs jar, with javac" +
        " of the early-access JDK."
    testClassesDirs = stubsTestSourceSet.get().output.classesDirs
    classpath = stubsTestSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    checkStubs(files(releaseStubsEarlyAccess), jdkEarlyAccessVersion)
}

tasks.named<JavaCompile>("compileStubsTestJava") {
    javaCompiler = javaToolchains.compilerFor { languageVersion = jdkTestVersion }
    // JUnit names each case by its parameters.
    options.compilerArgs.add("-parameters")
}

tasks.check {
    dependsOn(tasks.named("stubs"), testing.suites.named("stubsTest"))
}
