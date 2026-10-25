import ru.alexey.event.threads.codegen.source.ResourceConfigSource

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
    alias(libs.plugins.serialization)
    id("io.github.izzzgoy.event-thread.codegen")
}

// Generates typed scopes (`<Name>ScopeKey`, `<Name>Scope` with its container and handler bases)
// into commonMain (see build/generated/eventThread) from
// src/commonMain/resources/event-thread/scopes.json, and adds event-thread-core to commonMain's api. Any ConfigSource works here - e.g.
// `source<PlainConfigSource> { scopes { register("Todo") } }` to declare the schema in this file.
eventThreadCodegen {
    packageName = "org.company.sample"
    source<ResourceConfigSource> { path = "event-thread/scopes.json" }
    // The schema as last released: `check` fails if scopes.json's `version` isn't bumped as its
    // changes since then require (MAJOR/MINOR/PATCH). Refresh this copy at each release.
    baseline = layout.projectDirectory.file("event-thread/scopes.baseline.json")
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
            implementation(libs.event.thread.core)
            implementation(libs.event.thread.cache)
            implementation(libs.event.thread.secure)
            implementation(libs.event.thread.compose)
            implementation(libs.event.thread.koin)
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
            // The fake "Ideas" server behind the generated Ktor API (typed/FakeIdeasServer.kt).
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.event.thread.test)
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
