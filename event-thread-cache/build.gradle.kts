plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    kotlin("multiplatform")
    id("com.android.library")
    id("convention.publication")
}

version = "0.0.1-dev03"
group = "ru.alexey.event.threads.cache"

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
        jvmToolchain(11)
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
                implementation("io.github.xxfast:kstore:0.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.0")
                implementation(project(":event-thread-core"))
            }
        }

        jsMain {
            dependencies {
                implementation("io.github.xxfast:kstore-storage:0.6.0")
            }
        }

        iosMain {
            dependencies {
                implementation("io.github.xxfast:kstore-file:0.6.0")
            }
        }

        jvmMain {
            dependencies {
                implementation("net.harawata:appdirs:1.2.2")
                implementation("io.github.xxfast:kstore-file:0.6.0")
            }
        }
        androidMain {
            dependencies {
                implementation("io.github.xxfast:kstore-file:0.6.0")
            }
        }
    }
}

android {
    namespace = "ru.alexey.event.threads.cache"
    compileSdk = 33
    defaultConfig {
        minSdk = 24
    }
}