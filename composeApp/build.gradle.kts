plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
    alias(libs.plugins.serialization)
}



kotlin {
    jvmToolchain(17)

    androidTarget()

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":event-thread-core"))
            implementation(project(":event-thread-cache"))
            implementation(project(":event-thread-secure"))
            implementation(project(":event-thread-compose"))
            implementation(project(":event-thread-koin"))
            implementation(compose.runtime)
            implementation(compose.material3)
            // material-icons-extended/-core are frozen at 1.7.3 by JetBrains (superseded by Material
            // Symbols) - exclude its transitive Compose framework deps so it doesn't drag the old
            // compose.ui/foundation/runtime into the graph alongside our current version.
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3") {
                exclude(group = "org.jetbrains.compose.ui")
                exclude(group = "org.jetbrains.compose.foundation")
                exclude(group = "org.jetbrains.compose.runtime")
            }
            implementation(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":event-thread-test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.compose.ui.tooling)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

    }
}

android {
    namespace = "org.company.sample"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36

        applicationId = "org.company.sample.androidApp"
        versionCode = 1
        versionName = "1.0.0"
    }
    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        res.srcDirs("src/androidMain/resources")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}
