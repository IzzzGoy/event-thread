package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import ru.alexey.event.threads.codegen.model.ScopeDocument
import ru.alexey.event.threads.codegen.model.ScopeModel
import java.io.File

/**
 * Generator entry point: everything the specs produce, split by package (see [GeneratedPackages]) -
 * [ScopeGenerator] (`.scopes`), [EventsGenerator] (`.events`), [ContainersGenerator]
 * (`.containers`), [ModelsGenerator] (`.models`) - all reading one shared [GeneratorContext];
 * [TestSourcesGenerator] for the test source set; [ScopeReports] for docs.
 *
 * Pure function of its inputs - no Gradle API here, so it's unit-testable directly and reusable
 * from any front-end (Gradle task today, possibly KSP/CLI later).
 */
object TypedScopeSources {

    fun generate(packageName: String, scopes: List<ScopeModel>): List<FileSpec> =
        generate(packageName, ScopeDocument(scopes = scopes))

    /** [compose]: also `<Name>ScopeContent` composables, see [ComposeGenerator]. */
    fun generate(packageName: String, document: ScopeDocument, generatorVersion: String? = null, compose: Boolean = false): List<FileSpec> {
        val context = GeneratorContext(packageName, document.scopes, header(generatorVersion, document))
        val files = document.scopes.map { ScopeGenerator(packageName).generate(context, it) } +
            (if (compose) document.scopes.map { ComposeGenerator().generate(context, it) } else emptyList()) +
            EventsGenerator(packageName).generate(context) +
            ContainersGenerator(packageName).generate(context) +
            ModelsGenerator(packageName).generate(context) +
            schemaInfo(context, document)
        val clashes = files.groupBy { "${it.packageName}.${it.name}" }.filterValues { it.size > 1 }.keys
        require(clashes.isEmpty()) { "Specs generate clashing top-level types: $clashes" }
        return files
    }

    /** `ScopesSchema` - the versions the code was generated from, for logging or a runtime check. */
    private fun schemaInfo(context: GeneratorContext, document: ScopeDocument): FileSpec {
        val type = TypeSpec.objectBuilder("ScopesSchema")
            .addKdoc("The schema this code was generated from.")
            .addProperty(
                PropertySpec.builder("VERSION", STRING.copy(nullable = true))
                    .addKdoc("The schema's `version` - `null` if it has none.")
                    .initializer(if (document.version == null) "null" else "%S", *listOfNotNull(document.version).toTypedArray())
                    .build()
            )
            .addProperty(PropertySpec.builder("FORMAT", STRING, KModifier.CONST).initializer("%S", document.format).build())
            .build()
        return FileSpec.builder(context.packages.scopes, "ScopesSchema").generatedBy(context).addType(type).build()
    }

    /** Test support for the same schema - contracts and probes, see [TestSourcesGenerator]. */
    fun generateTests(packageName: String, scopes: List<ScopeModel>): List<FileSpec> =
        generateTests(packageName, ScopeDocument(scopes = scopes))

    fun generateTests(packageName: String, document: ScopeDocument, generatorVersion: String? = null): List<FileSpec> =
        TestSourcesGenerator(packageName).generate(GeneratorContext(packageName, document.scopes, header(generatorVersion, document)))

    fun writeTo(outputDirectory: File, packageName: String, scopes: List<ScopeModel>) =
        writeTo(outputDirectory, packageName, ScopeDocument(scopes = scopes))

    /** Writes every generated file under [outputDirectory] (as a source root - package dirs are
     * created beneath it). */
    fun writeTo(outputDirectory: File, packageName: String, document: ScopeDocument, generatorVersion: String? = null, compose: Boolean = false) {
        generate(packageName, document, generatorVersion, compose).forEach { it.writeTo(outputDirectory) }
    }

    fun writeTestsTo(outputDirectory: File, packageName: String, document: ScopeDocument, generatorVersion: String? = null) {
        generateTests(packageName, document, generatorVersion).forEach { it.writeTo(outputDirectory) }
    }
}
