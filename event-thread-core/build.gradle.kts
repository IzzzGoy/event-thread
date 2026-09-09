plugins {
    alias(libs.plugins.multiplatform)
    id("convention.publication-core")
    alias(libs.plugins.android.library)
    checkstyle
    alias(libs.plugins.serialization)
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
                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
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
