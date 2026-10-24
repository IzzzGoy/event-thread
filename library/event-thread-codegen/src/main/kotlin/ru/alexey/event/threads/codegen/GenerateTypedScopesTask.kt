package ru.alexey.event.threads.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import ru.alexey.event.threads.codegen.generator.TypedScopeSources
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import java.io.File

/** Runs [TypedScopeSources] over the schema from the selected
 * [ru.alexey.event.threads.codegen.source.ConfigSource] into [outputDirectory]. Registered and
 * wired into the Kotlin source sets by [EventThreadCodegenPlugin]. */
@CacheableTask
abstract class GenerateTypedScopesTask : DefaultTask() {

    @get:Input
    abstract val packageName: Property<String>

    /** The scope spec as [ScopeSpecFormat] JSON - a plain string, so Gradle fingerprints it for
     * up-to-date checks and the configuration cache without the model being `java.io.Serializable`. */
    @get:Input
    abstract val scopesSpec: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val scopes = ScopeSpecFormat.decode(scopesSpec.get())

        val dir = outputDirectory.get().asFile
        // Start clean so a removed/renamed scope doesn't leave a stale class behind.
        dir.deleteRecursively()
        dir.mkdirs()
        TypedScopeSources.writeTo(dir, packageName.get(), scopes)
    }
}
