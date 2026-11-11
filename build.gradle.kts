import java.time.LocalDate
import java.time.ZoneOffset

plugins {
    java
    `jvm-test-suite`
}

val jdkBuildVersion = providers.gradleProperty("jdkBuildVersion").map { JavaLanguageVersion.of(it) }
val jdkTestVersion = providers.gradleProperty("jdkTestVersion").map { JavaLanguageVersion.of(it) }
    .orElse(jdkBuildVersion)

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

fun TaskContainer.registerReleaseStubs(name: String, headers: FileCollection, outputDir: Provider<Directory>) =
    register<JavaExec>(name) {
        description = "Writes one stubs jar per selected Java release into ${outputDir.get().asFile.name}."
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
            )
        }
    }

fun TaskContainer.registerReproducibilityCheck(
    name: String,
    mainClass: String,
    first: TaskProvider<*>,
    second: TaskProvider<*>,
    vararg extraArgs: Provider<String>,
) =
    register<JavaExec>(name) {
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

tasks.register("stubs") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds build/out/jspecify-jdk-stubs-jdk<N>.jar and checks that the build is reproducible."
    dependsOn(releaseStubs, checkHeaders, checkReleaseStubs)
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
                    javaLauncher = javaToolchains.launcherFor { languageVersion = jdkTestVersion }
                    inputs.files(stubsDir).withPathSensitivity(PathSensitivity.NAME_ONLY)
                    val stubsJars = stubsDir
                    jvmArgumentProviders.add { listOf("-DstubsDir=${stubsJars.singleFile}") }
                    // Error Prone runs inside javac and reaches its internals.
                    jvmArgs(
                        listOf("api", "code", "comp", "file", "main", "model", "parser", "processing", "tree", "util")
                            .map { "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" },
                    )
                    jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
                }
            }
        }
    }
}

tasks.named<JavaCompile>("compileStubsTestJava") {
    javaCompiler = javaToolchains.compilerFor { languageVersion = jdkTestVersion }
    // JUnit names each case by its parameters.
    options.compilerArgs.add("-parameters")
}

tasks.check {
    dependsOn(tasks.named("stubs"), testing.suites.named("stubsTest"))
}
