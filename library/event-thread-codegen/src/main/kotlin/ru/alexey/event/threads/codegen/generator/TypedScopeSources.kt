package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.FileSpec
import ru.alexey.event.threads.codegen.model.ScopeModel
import java.io.File

/**
 * Generator entry point: everything the specs produce, split by package (see [GeneratedPackages]) -
 * [ScopeGenerator] (`.scopes`), [EventsGenerator] (`.events`), [ContainersGenerator]
 * (`.containers`), [ModelsGenerator] (`.models`) - all reading one shared [GeneratorContext].
 *
 * Pure function of its inputs - no Gradle API here, so it's unit-testable directly and reusable
 * from any front-end (Gradle task today, possibly KSP/CLI later).
 */
object TypedScopeSources {

    fun generate(packageName: String, scopes: List<ScopeModel>): List<FileSpec> {
        val context = GeneratorContext(packageName, scopes)
        val files = scopes.map { ScopeGenerator(packageName).generate(context, it) } +
            EventsGenerator(packageName).generate(context) +
            ContainersGenerator(packageName).generate(context) +
            ModelsGenerator(packageName).generate(context)
        val clashes = files.groupBy { "${it.packageName}.${it.name}" }.filterValues { it.size > 1 }.keys
        require(clashes.isEmpty()) { "Specs generate clashing top-level types: $clashes" }
        return files
    }

    /** Writes every generated file under [outputDirectory] (as a source root - package dirs are
     * created beneath it). */
    fun writeTo(outputDirectory: File, packageName: String, scopes: List<ScopeModel>) {
        generate(packageName, scopes).forEach { it.writeTo(outputDirectory) }
    }
}
