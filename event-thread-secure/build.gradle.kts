plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("convention.publication-secure")
}

version = project.rootProject.version
group = project.rootProject.group

kotlin {

    jvmToolchain(8)

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
    
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":event-thread-core"))
                implementation("com.liftric:kvault:1.12.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.0")

                implementation("io.realm.kotlin:library-base:1.12.0")

                implementation("dev.whyoleg.cryptography:cryptography-core:0.2.0")
            }
        }
        /*jvmMain {
            dependencies {
                implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.2.0")
            }
        }*/
        androidMain {
            dependencies {
                implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.2.0")
            }
        }
        /*jsMain {
            dependencies {
                implementation("dev.whyoleg.cryptography:cryptography-provider-webcrypto:0.2.0")
            }
        }*/
        iosMain {
            dependencies {
                implementation("dev.whyoleg.cryptography:cryptography-provider-apple:0.2.0")
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
