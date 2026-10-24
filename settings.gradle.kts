pluginManagement {
    // event-thread-codegen's Gradle plugin, built from source.
    includeBuild("library")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        mavenLocal()
    }
}

plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "research"

// The library itself: `io.github.izzzgoy:event-thread-*` dependencies resolve to its projects.
includeBuild("library")
include("composeApp")
