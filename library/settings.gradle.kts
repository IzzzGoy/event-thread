pluginManagement {
    includeBuild("convention-plugins")
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
    // One catalog for the library and the sample app (repo root) alike.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Everything published as io.github.izzzgoy:event-thread-*. Its own build (included by the
// sample app at the repo root) so the sample can apply event-thread-codegen as a real plugin -
// a plugin can't be applied by modules of the same build that compiles it.
rootProject.name = "event-thread-library"
include("event-thread-core")
include("event-thread-test")
include("event-thread-cache")
include("event-thread-secure")
include("event-thread-compose")
include("event-thread-network")
include("event-thread-koin")
include("event-thread-codegen")
