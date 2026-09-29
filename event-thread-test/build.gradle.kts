plugins {
    alias(libs.plugins.multiplatform)
    id("convention.publication-test")
    alias(libs.plugins.android.library)
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
                // `api`, not `implementation`: `ScenarioTestRunner.runScenario`'s public signature
                // exposes `TestScope`/`TestResult` directly, so a consumer needs these types on
                // its own compile classpath too, not just this module's.
                api(libs.kotlinx.coroutines.test)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "ru.alexey.event.threads.test"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
}
