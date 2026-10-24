package ru.alexey.event.threads.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import ru.alexey.event.threads.codegen.generator.SchemaCompatibility
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat

/**
 * Semver for the schema: classifies its changes against [baseline] (the schema as last released)
 * and fails if `version` wasn't bumped enough - see [SchemaCompatibility] for what's MAJOR, MINOR,
 * PATCH. Writes the classification to [reportFile] either way. Without a baseline there's nothing
 * to compare, and it does nothing.
 */
@CacheableTask
abstract class CheckScopesCompatibilityTask : DefaultTask() {

    @get:Input
    abstract val scopesSpec: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun check() {
        val report = reportFile.get().asFile.apply { parentFile.mkdirs() }
        if (!baseline.isPresent) {
            report.writeText("No baseline configured - nothing to compare.\n")
            return
        }
        val result = SchemaCompatibility.check(
            baseline = ScopeSpecFormat.decodeDocument(baseline.get().asFile.readText()),
            current = ScopeSpecFormat.decodeDocument(scopesSpec.get()),
        )
        report.writeText(result.render())
        result.problem?.let { throw IllegalStateException("event-thread schema: $it\n\n${result.render()}") }
        logger.lifecycle("event-thread schema: changes need ${result.required.name.lowercase()} - version consistent")
    }
}
