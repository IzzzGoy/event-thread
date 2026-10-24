package ru.alexey.event.threads.codegen.source

import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import ru.alexey.event.threads.codegen.model.ScopeDocument
import ru.alexey.event.threads.codegen.model.ScopeModel
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import java.io.File
import javax.inject.Inject

/**
 * The schema read from a [ScopeSpecFormat] JSON file in the project's resources - those of the
 * source set generated code lands in ([ConfigSourceContext.resourceDirectories]), e.g.
 * `src/commonMain/resources/event-thread/scopes.json`:
 * ```
 * eventThreadCodegen {
 *     source<ResourceConfigSource> { path = "event-thread/scopes.json" }
 * }
 * ```
 * Note the file is then an ordinary resource too, so it's packaged with that source set's output.
 */
abstract class ResourceConfigSource @Inject constructor(
    private val providers: ProviderFactory,
    private val layout: ProjectLayout,
) : ConfigSource() {

    /** Path of the schema, relative to a resources root. Defaults to [DEFAULT_PATH]. */
    abstract val path: Property<String>

    init {
        path.convention(DEFAULT_PATH)
    }

    override fun schema(context: ConfigSourceContext): Provider<List<ScopeModel>> = document(context).map { it.scopes }

    override fun document(context: ConfigSourceContext): Provider<ScopeDocument> {
        // fileContents, not readText(): the configuration cache records the read, so editing the
        // file invalidates a cached configuration instead of replaying the old schema. Checked
        // here rather than via `orElse(<throwing provider>)` - Gradle evaluates an orElse fallback
        // even when the file's contents are present, since they aren't a fixed value.
        return path.flatMap { relative ->
            val file = locate(context, relative)
            check(file.isFile) { "No event-thread schema at $file (`path` of ResourceConfigSource)" }
            providers.fileContents(layout.projectDirectory.file(file.absolutePath)).asText
        }.map(ScopeSpecFormat::decodeDocument)
    }

    /** The first resource root that has [relative]; if none does, the first root's would-be
     * location, so the error names where the schema was expected. */
    private fun locate(context: ConfigSourceContext, relative: String): File {
        val roots = context.resourceDirectories.files
            .ifEmpty { setOf(layout.projectDirectory.dir(FALLBACK_ROOT).asFile) }
        return roots.map { it.resolve(relative) }.firstOrNull { it.isFile } ?: roots.first().resolve(relative)
    }

    companion object {
        const val DEFAULT_PATH = "event-thread/scopes.json"

        /** Used when no Kotlin plugin is applied, so there are no source-set resource roots. */
        private const val FALLBACK_ROOT = "src/main/resources"
    }
}
