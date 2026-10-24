package ru.alexey.event.threads.codegen

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigSourceTest {

    private val projectDir: File = createTempDirectory("codegen-source").toFile()

    @AfterTest
    fun cleanup() {
        projectDir.deleteRecursively()
    }

    private fun buildScript(body: String, plugins: String = "", extra: String = "") {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "sample"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            import ru.alexey.event.threads.codegen.source.*
            import ru.alexey.event.threads.codegen.model.ScopeModel

            plugins {
                $plugins
                id("io.github.izzzgoy.event-thread.codegen")
            }

            eventThreadCodegen {
                packageName = "com.example"
                $body
            }

            $extra
            """.trimIndent()
        )
    }

    private fun spec(relative: String, vararg scopes: String): File =
        projectDir.resolve(relative).apply { parentFile.mkdirs() }
            .apply { writeText(scopes.joinToString(prefix = "[", postfix = "]") { """{ "name": "$it" }""" }) }

    private fun runner() = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(EventThreadCodegenPlugin.TASK_NAME, "--configuration-cache")

    private fun generated(name: String) =
        projectDir.resolve("build/generated/eventThread/kotlin/com/example/scopes/$name.kt")

    @Test
    fun plainSourceUsesBuildScriptScopes() {
        buildScript("""source<PlainConfigSource> { scopes { register("Todo") } }""")

        runner().build()

        assertTrue(generated("TodoScope").exists())
    }

    @Test
    fun resourceSourceReadsDefaultPath() {
        buildScript("source<ResourceConfigSource>()")
        spec("src/main/resources/event-thread/scopes.json", "Todo")

        runner().build()

        assertTrue(generated("TodoScope").exists())
    }

    /** Whether Gradle re-reads the file at execution or recalculates the configuration is its
     * call - what matters is that a cached configuration never replays the old schema. */
    @Test
    fun editingResourceRegeneratesUnderConfigurationCache() {
        buildScript("""source<ResourceConfigSource> { path = "spec.json" }""")
        spec("src/main/resources/spec.json", "Todo")
        runner().build()

        spec("src/main/resources/spec.json", "Settings")
        runner().build()

        assertTrue(generated("SettingsScope").exists())
        assertFalse(generated("TodoScope").exists())
    }

    @Test
    fun resourceIsFoundInKotlinSourceSetResourceRoots() {
        buildScript(
            "source<ResourceConfigSource>()",
            plugins = """id("org.jetbrains.kotlin.jvm")""",
            extra = """sourceSets.main { resources.srcDir("spec") }""",
        )
        spec("spec/event-thread/scopes.json", "Todo")

        runner().build()

        assertTrue(generated("TodoScope").exists())
    }

    @Test
    fun missingResourceNamesExpectedLocation() {
        buildScript("source<ResourceConfigSource>()")

        val result = runner().buildAndFail()

        assertContains(result.output, "No event-thread schema at")
        assertContains(result.output, "src/main/resources/event-thread/scopes.json")
    }

    @Test
    fun urlSourceIsRefetchedOnEveryBuild() {
        val remote = spec("remote/scopes.json", "Todo")
        buildScript("""source<UrlConfigSource> { url = "${remote.toURI()}" }""")
        runner().build()
        assertTrue(generated("TodoScope").exists())

        spec("remote/scopes.json", "Settings")
        runner().build()

        assertTrue(generated("SettingsScope").exists())
        assertFalse(generated("TodoScope").exists())
    }

    /** The extension point itself: a source defined by the consumer, outside the plugin. */
    @Test
    fun customSourceFromBuildScript() {
        buildScript(
            """source<FixedSource> { names = listOf("Todo", "Settings") }""",
            extra = """
                abstract class FixedSource @Inject constructor(private val providers: ProviderFactory) : ConfigSource() {
                    abstract val names: ListProperty<String>
                    override fun schema(context: ConfigSourceContext) = names.map { all -> all.map { ScopeModel(it) } }
                }
            """.trimIndent(),
        )

        runner().build()

        assertTrue(generated("TodoScope").exists())
        assertTrue(generated("SettingsScope").exists())
    }

    @Test
    fun sameSourceTwiceConfiguresOneInstance() {
        buildScript(
            """
            source<PlainConfigSource> { scopes { register("Todo") } }
            source<PlainConfigSource> { scopes { register("Settings") } }
            """.trimIndent()
        )

        runner().build()

        assertTrue(generated("TodoScope").exists())
        assertTrue(generated("SettingsScope").exists())
    }

    @Test
    fun selectingTwoSourcesFails() {
        buildScript(
            """
            source<PlainConfigSource> { scopes { register("Todo") } }
            source<ResourceConfigSource>()
            """.trimIndent()
        )

        val result = runner().buildAndFail()

        assertContains(result.output, "selects both PlainConfigSource and ResourceConfigSource")
    }

    @Test
    fun noSourceFailsWithHint() {
        buildScript("")

        val result = runner().buildAndFail()

        assertContains(result.output, "has no schema source")
    }
}
