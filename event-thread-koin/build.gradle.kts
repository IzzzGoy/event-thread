plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    id("convention.publication-koin")
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
                api(project(":event-thread-core"))
                api(libs.koin.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "ru.alexey.event.threads.koin"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
}
