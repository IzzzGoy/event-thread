package ru.alexey.event.threads.codegen

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CoreDependencyWiringTest {

    private val rootDir: File = createTempDirectory("codegen-wiring").toFile()

    @AfterTest
    fun cleanup() {
        rootDir.deleteRecursively()
    }

    /** Prints `api` dependencies as declared - not resolved, so no repository is needed. */
    private fun appBuildScript(extra: String = "") = """
        plugins {
            id("org.jetbrains.kotlin.jvm")
            id("io.github.izzzgoy.event-thread.codegen")
        }

        eventThreadCodegen {
            packageName = "com.example"
            $extra
        }

        tasks.register("printApi") {
            val deps = provider { configurations["api"].dependencies.map { "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}" } }
            doLast { deps.get().forEach { println("API " + it) } }
        }
    """.trimIndent()

    private fun apiDependencies(): List<String> =
        GradleRunner.create()
            .withProjectDir(rootDir)
            .withPluginClasspath()
            .withArguments("printApi")
            .build()
            .output.lines()
            .filter { it.startsWith("API ") }
            .map { it.removePrefix("API ") }

    @Test
    fun standaloneConsumerGetsPublishedCoreAtPluginVersion() {
        rootDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "app"""")
        rootDir.resolve("build.gradle.kts").writeText(appBuildScript())

        assertContains(
            apiDependencies(),
            "io.github.izzzgoy:event-thread-core:${EventThreadCodegenPlugin.pluginVersion}",
        )
    }

    @Test
    fun coreVersionOverrideIsUsed() {
        rootDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "app"""")
        rootDir.resolve("build.gradle.kts").writeText(appBuildScript("""coreVersion = "9.9.9""""))

        assertContains(apiDependencies(), "io.github.izzzgoy:event-thread-core:9.9.9")
    }

    /** Prints `testImplementation` dependencies - where the generated contracts/probes need
     * event-thread-test and kotlin-test. */
    @Test
    fun testSupportDependenciesGoToTheTestSourceSet() {
        rootDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "app"""")
        rootDir.resolve("build.gradle.kts").writeText(appBuildScript().replace("configurations[\"api\"]", "configurations[\"testImplementation\"]"))

        val deps = apiDependencies()
        assertContains(deps, "io.github.izzzgoy:event-thread-test:${EventThreadCodegenPlugin.pluginVersion}")
        assertContains(deps, "org.jetbrains.kotlin:kotlin-test:null")
    }

    @Test
    fun optOutAddsNothing() {
        rootDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "app"""")
        rootDir.resolve("build.gradle.kts").writeText(appBuildScript("addCoreDependency = false"))

        assertFalse(apiDependencies().any { it.contains("event-thread-core") })
    }
}
