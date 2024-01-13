plugins {
    kotlin("multiplatform") version "1.9.20" apply false
    id("com.android.library") version "8.1.2" apply false

    id("org.jetbrains.compose") version "1.5.10" apply false
    id("com.android.application") version "8.1.2" apply false
    id("io.github.skeptick.libres") version "1.2.1" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.6.0" apply false
}

version = "0.2.0"
group = "io.github.izzzgoy"

repositories {
    google()
    mavenCentral()
}