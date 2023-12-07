plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("com.android.application")
    id("io.github.skeptick.libres")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    listOf(
        iosX64(),
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
            implementation(compose.runtime)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation("io.github.skeptick.libres:libres-compose:1.2.1")
            implementation("cafe.adriel.voyager:voyager-navigator:1.0.0-rc09")
            implementation("co.touchlab:kermit:2.0.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            implementation("io.ktor:ktor-client-core:2.3.5")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            //implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation("androidx.appcompat:appcompat:1.6.1")
            implementation("androidx.activity:activity-compose:1.8.0")
            implementation("androidx.compose.ui:ui-tooling:1.5.4")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
            implementation("io.ktor:ktor-client-okhttp:2.3.5")
        }

        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:2.3.5")
        }

    }
}

android {
    namespace = "org.company.sample"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34

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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }
}


/*libres {
    // https://github.com/Skeptick/libres#setup
}*/
