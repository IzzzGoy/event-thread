plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    kotlin("multiplatform")
    id("com.android.library")
    id("convention.publication-core")
    checkstyle
}


repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
    google()
}

version = project.rootProject.version
group = project.rootProject.group

kotlin {
    applyDefaultHierarchyTemplate()

    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    jvm {
        jvmToolchain(8)
    }
    js(IR) {
        binaries.executable()
        browser {
            testTask {
                useKarma {
                    useSafari()
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":event-thread-core"))
                implementation("io.ktor:ktor-client-core:3.0.0-beta-1")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0-beta-1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0-beta-1")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
    }

}

android {
    namespace = "ru.alexey.event.threads"
    compileSdk = 33
    defaultConfig {
        minSdk = 24
    }
}
