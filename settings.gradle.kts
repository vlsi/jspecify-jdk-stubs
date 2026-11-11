plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jspecify-jdk-stubs"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
