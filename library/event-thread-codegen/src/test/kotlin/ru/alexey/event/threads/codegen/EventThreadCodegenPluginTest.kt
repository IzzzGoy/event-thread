package ru.alexey.event.threads.codegen

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventThreadCodegenPluginTest {

    private val projectDir: File = createTempDirectory("codegen-test").toFile()

    @AfterTest
    fun cleanup() {
        projectDir.deleteRecursively()
    }

    private fun buildScript(scopes: String) {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "sample"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import ru.alexey.event.threads.codegen.source.PlainConfigSource

            plugins { id("io.github.izzzgoy.event-thread.codegen") }

            eventThreadCodegen {
                packageName = "com.example"
                source<PlainConfigSource> {
                    scopes {
                        $scopes
                    }
                }
            }
            """.trimIndent()
        )
    }

    private fun run() = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(EventThreadCodegenPlugin.TASK_NAME, "--configuration-cache")
        .build()

    private val generatedDir get() = projectDir.resolve("build/generated/eventThread/kotlin/com/example/scopes")

    @Test
    fun generatesDeclaredScopesAndIsUpToDateOnRerun() {
        buildScript("""register("Todo") { description = "Todo list" }""")

        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":${EventThreadCodegenPlugin.TASK_NAME}")?.outcome)
        assertContains(generatedDir.resolve("TodoScope.kt").readText(), "class TodoScope")

        val second = run()
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":${EventThreadCodegenPlugin.TASK_NAME}")?.outcome)
    }

    @Test
    fun removedScopeLeavesNoStaleFile() {
        buildScript("""register("Todo"); register("Settings")""")
        run()
        assertTrue(generatedDir.resolve("SettingsScope.kt").exists())

        buildScript("""register("Todo")""")
        run()
        assertFalse(generatedDir.resolve("SettingsScope.kt").exists())
        assertTrue(generatedDir.resolve("TodoScope.kt").exists())
    }

    private val testDir get() = projectDir.resolve("build/generated/eventThread/test/com/example/scopes")

    @Test
    fun testSupportIsGeneratedUnlessTurnedOff() {
        buildScript("""register("Todo")""")
        run()
        assertTrue(testDir.resolve("TodoScopeContract.kt").exists())
        assertTrue(testDir.resolve("TodoScopeProbe.kt").exists())

        projectDir.resolve("build.gradle.kts").appendText("\neventThreadCodegen { generateTests = false }\n")
        run()
        assertFalse(testDir.resolve("TodoScopeContract.kt").exists())
    }
}
