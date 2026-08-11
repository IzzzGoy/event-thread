plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    id("convention.publication-secure")
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

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":event-thread-core"))
                implementation(libs.kvault)
                implementation(libs.kotlinx.serialization.cbor)

                implementation(libs.realm.library.base)

                implementation(libs.cryptography.core)
            }
        }
        /*jvmMain {
            dependencies {
                implementation(libs.cryptography.provider.jdk)
            }
        }*/
        androidMain {
            dependencies {
                implementation(libs.cryptography.provider.jdk)
            }
        }
        /*jsMain {
            dependencies {
                implementation(libs.cryptography.provider.webcrypto)
            }
        }*/
        iosMain {
            dependencies {
                implementation(libs.cryptography.provider.apple)
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
