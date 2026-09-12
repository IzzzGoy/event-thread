plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.serialization) apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

version = "1.0.0-RC2"
group = "io.github.izzzgoy"

repositories {
    google()
    mavenCentral()
}