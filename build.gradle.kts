import org.jetbrains.kotlin.scripting.compiler.plugin.impl.failure

plugins {
    kotlin("multiplatform") version "1.9.10" apply false
    id("com.android.library") version "8.0.0" apply false
}

repositories {
    google()
    mavenCentral()
}