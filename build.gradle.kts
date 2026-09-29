plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.serialization) apply false
}

version = "1.0.0-alpha14"
group = "io.github.izzzgoy"

repositories {
    google()
    mavenCentral()
}