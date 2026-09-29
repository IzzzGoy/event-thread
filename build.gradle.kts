plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.serialization) apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

// CI passes -PreleaseVersion=<tag without "v">; local builds keep the default.
version = providers.gradleProperty("releaseVersion").getOrElse("1.0.0")
group = "io.github.izzzgoy"

repositories {
    google()
    mavenCentral()
}