package ru.alexey.event.threads.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import ru.alexey.event.threads.codegen.generator.ScopeReports
import ru.alexey.event.threads.codegen.generator.SchemaValidator
import ru.alexey.event.threads.codegen.generator.TypedScopeSources
import ru.alexey.event.threads.codegen.model.ScopeJsonSchema
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import java.io.File

/** Runs [TypedScopeSources] over the schema from the selected
 * [ru.alexey.event.threads.codegen.source.ConfigSource] into [outputDirectory] (and test support
 * into [testOutputDirectory]), plus the [ScopeReports] and the format's JSON Schema into
 * [reportDirectory]. Registered and wired into the Kotlin source sets by [EventThreadCodegenPlugin]. */
@CacheableTask
abstract class GenerateTypedScopesTask : DefaultTask() {

    @get:Input
    abstract val packageName: Property<String>

    /** The schema document as [ScopeSpecFormat] JSON - a plain string, so Gradle fingerprints it for
     * up-to-date checks and the configuration cache without the model being `java.io.Serializable`. */
    @get:Input
    abstract val scopesSpec: Property<String>

    @get:Input
    abstract val generatorVersion: Property<String>

    @get:Input
    abstract val generateTests: Property<Boolean>

    /** Ktor implementations of HTTP remote APIs (event-thread-network). */
    @get:Input
    abstract val generateKtor: Property<Boolean>

    /** Also `<Name>ScopeContent` composables (event-thread-compose). */
    @get:Input
    abstract val generateCompose: Property<Boolean>

    /** Schema warnings (e.g. cascade loops) fail generation instead of being logged. */
    @get:Input
    abstract val strictSchema: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Test support (contracts, probes) - added to the test source set. */
    @get:OutputDirectory
    abstract val testOutputDirectory: DirectoryProperty

    /** Where the reports go: `scopes.md` (event-flow diagram), `tasks/<Scope>.md` (what to
     * implement), `scopes.schema.json` (the format's JSON Schema). Shared with
     * `checkScopesCompatibility`'s `compatibility.txt` - so only this task's own files are its
     * outputs (and cleaned), never the whole directory. */
    @get:Internal
    abstract val reportDirectory: DirectoryProperty

    @get:OutputFile
    val diagramFile: Provider<RegularFile> get() = reportDirectory.file("scopes.md")

    @get:OutputFile
    val jsonSchemaFile: Provider<RegularFile> get() = reportDirectory.file("scopes.schema.json")

    @get:OutputDirectory
    val tasksDirectory: Provider<Directory> get() = reportDirectory.dir("tasks")

    @TaskAction
    fun generate() {
        val document = ScopeSpecFormat.decodeDocument(scopesSpec.get())
        val report = SchemaValidator.report(document.scopes)
        report.warnings.forEach { logger.warn("event-thread schema: $it") }
        SchemaValidator.validate(document.scopes, strict = strictSchema.get())

        val version = generatorVersion.get()
        val dir = fresh(outputDirectory.get().asFile)
        TypedScopeSources.writeTo(dir, packageName.get(), document, version, compose = generateCompose.get(), ktor = generateKtor.get())

        val testDir = fresh(testOutputDirectory.get().asFile)
        if (generateTests.get()) TypedScopeSources.writeTestsTo(testDir, packageName.get(), document, version, ktor = generateKtor.get())

        diagramFile.get().asFile.apply { parentFile.mkdirs() }.writeText(ScopeReports.diagram(document))
        jsonSchemaFile.get().asFile.writeText(ScopeJsonSchema.generate())
        val tasks = fresh(tasksDirectory.get().asFile)
        ScopeReports.tasks(packageName.get(), document).forEach { (name, text) -> tasks.resolve(name).writeText(text) }
    }

    // Start clean so a removed/renamed scope doesn't leave a stale file behind.
    private fun fresh(dir: File): File = dir.apply { deleteRecursively(); mkdirs() }
}
