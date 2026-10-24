package ru.alexey.event.threads.codegen

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import ru.alexey.event.threads.codegen.source.ConfigSourceContext
import java.util.Properties

/**
 * Gradle entry point (`id("io.github.izzzgoy.event-thread.codegen")`): adds the
 * [EventThreadCodegenExtension] DSL, registers [GenerateTypedScopesTask], and - once a Kotlin
 * plugin is applied - adds its output to `commonMain` (multiplatform) or `main` (JVM/Android),
 * plus event-thread-core as that source set's `api` dependency, so the generated code always has
 * core to compile against and compiling the consumer generates first.
 */
class EventThreadCodegenPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create(
            EventThreadCodegenExtension.NAME,
            EventThreadCodegenExtension::class.java,
        ).apply {
            addCoreDependency.convention(true)
            generateTests.convention(true)
            strictSchema.convention(false)
            coreVersion.convention(pluginVersion)
            selectedSource.finalizeValueOnRead()
        }

        // Filled once a Kotlin plugin shows up (below); sources only ever see it read-only.
        val resourceDirectories = project.objects.fileCollection()
        val sourceContext = ConfigSourceContext(resourceDirectories)

        // The schema document, shared by generation and the compatibility check.
        val spec = project.provider {
            extension.selectedSource.orNull
                ?: error("`${EventThreadCodegenExtension.NAME}` has no schema source - add e.g. `source<ResourceConfigSource> { }`")
        }
            .flatMap { it.document(sourceContext) }
            .map(ScopeSpecFormat::encodeDocument)

        val generate = project.tasks.register(TASK_NAME, GenerateTypedScopesTask::class.java) { task ->
            task.group = "event thread"
            task.description = "Generates abstract typed scopes from the spec selected in `${EventThreadCodegenExtension.NAME}`."
            task.packageName.set(extension.packageName)
            task.scopesSpec.set(spec)
            task.outputDirectory.set(project.layout.buildDirectory.dir("generated/eventThread/kotlin"))
            task.generateTests.set(extension.generateTests)
            task.strictSchema.set(extension.strictSchema)
            task.generatorVersion.set(pluginVersion)
            task.testOutputDirectory.set(project.layout.buildDirectory.dir("generated/eventThread/test"))
            task.reportDirectory.set(project.layout.buildDirectory.dir("reports/eventThread"))
        }

        val compatibility = project.tasks.register(COMPATIBILITY_TASK_NAME, CheckScopesCompatibilityTask::class.java) { task ->
            task.group = "verification"
            task.description = "Checks the event-thread schema's version against its changes since `${EventThreadCodegenExtension.NAME}.baseline`."
            task.scopesSpec.set(spec)
            task.baseline.set(extension.baseline)
            task.reportFile.set(project.layout.buildDirectory.file("reports/eventThread/compatibility.txt"))
        }
        project.tasks.matching { it.name == "check" }.configureEach { it.dependsOn(compatibility) }

        KOTLIN_PLUGINS.forEach { (pluginId, sourceSetName) ->
            project.plugins.withId(pluginId) {
                val kotlin = project.extensions.getByType(KotlinProjectExtension::class.java)
                val sourceSet = kotlin.sourceSets.getByName(sourceSetName)

                // Test support: generated contracts/probes + what they're built on.
                val testSourceSet = kotlin.sourceSets.getByName(TEST_SOURCE_SETS.getValue(sourceSetName))
                testSourceSet.kotlin.srcDir(generate.flatMap { it.testOutputDirectory })
                project.configurations.named(testSourceSet.implementationConfigurationName) { impl ->
                    impl.dependencies.addAllLater(project.provider {
                        if (extension.generateTests.get()) {
                            listOf(
                                project.dependencies.create("$TEST_COORDINATES:${extension.coreVersion.get()}"),
                                project.dependencies.create("org.jetbrains.kotlin:kotlin-test"),
                            )
                        } else {
                            emptyList()
                        }
                    })
                }
                // A task-backed provider, so the dependency on `generate` is implicit.
                sourceSet.kotlin.srcDir(generate.flatMap { it.outputDirectory })
                // Plain directory paths, not `resources.sourceDirectories` itself: that collection
                // can carry task-produced roots (compose/Android generated resources), and a spec
                // provider built on it then can't be resolved when the configuration cache is
                // stored - Gradle serializes the whole producer chain instead of one file path.
                resourceDirectories.from(project.provider { sourceSet.resources.srcDirs })

                // `addAllLater` so `addCoreDependency`/`coreVersion` are read when the
                // configuration resolves, not now - the build script's `eventThreadCodegen { }`
                // block typically runs after this hook already fired.
                project.configurations.named(sourceSet.apiConfigurationName) { api ->
                    api.dependencies.addAllLater(project.provider {
                        if (extension.addCoreDependency.get()) {
                            listOf(project.dependencies.create("$CORE_COORDINATES:${extension.coreVersion.get()}"))
                        } else {
                            emptyList()
                        }
                    })
                }
            }
        }
    }

    companion object {
        const val TASK_NAME = "generateTypedScopes"
        const val COMPATIBILITY_TASK_NAME = "checkScopesCompatibility"

        private const val CORE_COORDINATES = "io.github.izzzgoy:event-thread-core"
        private const val TEST_COORDINATES = "io.github.izzzgoy:event-thread-test"

        private val TEST_SOURCE_SETS = mapOf("commonMain" to "commonTest", "main" to "test")

        private val KOTLIN_PLUGINS = mapOf(
            "org.jetbrains.kotlin.multiplatform" to "commonMain",
            "org.jetbrains.kotlin.jvm" to "main",
            "org.jetbrains.kotlin.android" to "main",
        )

        /** This plugin's version, baked in at build time (see `processResources` in the plugin's
         * build script). */
        val pluginVersion: String by lazy {
            val props = Properties()
            EventThreadCodegenPlugin::class.java
                .getResourceAsStream("/META-INF/event-thread-codegen.properties")
                ?.use(props::load)
            props.getProperty("version") ?: error("event-thread-codegen version resource missing")
        }
    }
}
