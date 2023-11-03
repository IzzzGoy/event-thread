import java.util.*

plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    kotlin("multiplatform")
    id("com.android.library")
    id("maven-publish")
}


val props = Properties()
props.load(project.rootProject.file("local.properties").inputStream())
val token = props.getProperty("token") ?: System.getProperty("token") ?: error("Missing token!")

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/IzzzGoy/event-thread")
            credentials {
                username = "IzzzGoy"
                password = token
            }
        }
    }
    publications {
        register<MavenPublication>("gpr") {
            groupId = "ru.alexey.event.threads"
            artifactId = "core"
            version = "0.0.1"
            from(components.first())
        }
    }
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
    google()
}


kotlin {
    targetHierarchy.default()

    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }
    iosX64()
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
        val commonMain by getting {
            
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
    }
    
}

android {
    namespace = "ru.alexey.event.threads"
    compileSdk = 33
    defaultConfig {
        minSdk = 24
    }
}
