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
        val commonMain by getting {

            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val commonTest by getting {
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
