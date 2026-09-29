plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    // Only needed for this module's own tests - production code's `T`s are always supplied by
    // the caller's own module (which applies its own serialization plugin); a `@Serializable`
    // test fixture class declared *inside* this module needs it too, though.
    alias(libs.plugins.serialization)
    id("convention.publication-network")
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
                implementation(project(":event-thread-core"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.resources)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.websockets)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }

}

android {
    namespace = "ru.alexey.event.threads"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
}
