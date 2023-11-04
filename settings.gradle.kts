
pluginManagement {
 repositories {
  google()
  gradlePluginPortal()
  mavenCentral()
 }
}

dependencyResolutionManagement {
 repositories {
  google()
  mavenCentral()
 }
}

plugins {
 // Apply the foojay-resolver plugin to allow automatic download of JDKs
 id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}

rootProject.name = "research"
include("event-thread-core")
includeBuild("convention-plugins")
