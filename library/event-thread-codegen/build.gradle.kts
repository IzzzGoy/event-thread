import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.serialization)
    id("convention.publication-codegen")
}

version = project.rootProject.version
group = project.rootProject.group

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Gradle runs plugins against its own embedded Kotlin stdlib (2.0.x on Gradle 8.14), not
        // the one this build compiles with - don't let the compiler reach for newer stdlib API.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

// KGP is compileOnly (the consumer brings its own), so TestKit wouldn't see it - put it on the
// plugin-under-test classpath explicitly, or a test build applying a Kotlin plugin can't load ours.
val testPluginClasspath: Configuration by configurations.creating

dependencies {
    // Core's JVM variant: generator code names core types by class reference (see CoreSymbols),
    // so renaming/moving one in core breaks this build instead of silently breaking generated code.
    implementation(projects.eventThreadCore)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinx.serialization.json)
    // Only to hook generated sources into the consumer's Kotlin source sets - the consumer's own
    // Kotlin Gradle Plugin is the one actually on the classpath at runtime.
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testPluginClasspath(libs.kotlin.gradle.plugin)
}

tasks.test {
    useJUnitPlatform()
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(testPluginClasspath)
}

// Bakes this plugin's own version in, so consumers get the matching event-thread-core by default
// (see EventThreadCodegenExtension.coreVersion).
tasks.processResources {
    val pluginVersion = version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("META-INF/event-thread-codegen.properties") {
        expand("version" to pluginVersion)
    }
}

gradlePlugin {
    website.set("https://github.com/IzzzGoy/event-thread")
    vcsUrl.set("https://github.com/IzzzGoy/event-thread")
    plugins {
        create("eventThreadCodegen") {
            id = "io.github.izzzgoy.event-thread.codegen"
            implementationClass = "ru.alexey.event.threads.codegen.EventThreadCodegenPlugin"
            displayName = "Event Thread Codegen"
            description = "Generates typed, abstract Event Thread scopes for developers to implement"
            tags.set(listOf("kotlin", "multiplatform", "codegen", "event-thread"))
        }
    }
}
