plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    id("convention.publication-cache")
}

version = project.rootProject.version
group = project.rootProject.group

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    androidTarget {
        publishLibraryVariants("release")
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()
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
                implementation(libs.kstore)
                implementation(libs.kotlinx.serialization.json.okio)
                implementation(libs.kotlinx.serialization.cbor)
                implementation(project(":event-thread-core"))

                api(libs.okio)
            }
        }

        jsMain {
            dependencies {
                implementation(libs.kstore.storage)
            }
        }

        iosMain {
            dependencies {
                implementation(libs.kstore.file)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.appdirs)
                implementation(libs.kstore.file)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.kstore.file)
            }
        }
    }
}

android {
    namespace = "ru.alexey.event.threads.cache"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
}