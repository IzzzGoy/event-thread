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
            coreVersion.convention(pluginVersion)
            selectedSource.finalizeValueOnRead()
        }

        // Filled once a Kotlin plugin shows up (below); sources only ever see it read-only.
        val resourceDirectories = project.objects.fileCollection()
        val sourceContext = ConfigSourceContext(resourceDirectories)

        val generate = project.tasks.register(TASK_NAME, GenerateTypedScopesTask::class.java) { task ->
            task.group = "event thread"
            task.description = "Generates abstract typed scopes from the spec selected in `${EventThreadCodegenExtension.NAME}`."
            task.packageName.set(extension.packageName)
            task.scopesSpec.set(
                project.provider {
                    extension.selectedSource.orNull
                        ?: error("`${EventThreadCodegenExtension.NAME}` has no schema source - add e.g. `source<ResourceConfigSource> { }`")
                }
                    .flatMap { it.schema(sourceContext) }
                    .map(ScopeSpecFormat::encode)
            )
            task.outputDirectory.set(project.layout.buildDirectory.dir("generated/eventThread/kotlin"))
        }

        KOTLIN_PLUGINS.forEach { (pluginId, sourceSetName) ->
            project.plugins.withId(pluginId) {
                val sourceSet = project.extensions.getByType(KotlinProjectExtension::class.java)
                    .sourceSets.getByName(sourceSetName)
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

        private const val CORE_COORDINATES = "io.github.izzzgoy:event-thread-core"

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
