package ru.alexey.event.threads.codegen.source

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import ru.alexey.event.threads.codegen.model.ScopeModel
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import java.net.URI
import javax.inject.Inject

/**
 * The schema fetched from a URL serving [ScopeSpecFormat] JSON (`https://`, `file://`, ...):
 * ```
 * eventThreadCodegen {
 *     source<UrlConfigSource> { url = "https://example.com/event-thread/scopes.json" }
 * }
 * ```
 * Fetched through a [ValueSource], which the configuration cache re-runs on every build to check
 * the cached configuration is still valid - so a changed schema is always picked up, at the cost
 * of one fetch per build.
 */
abstract class UrlConfigSource @Inject constructor(
    private val providers: ProviderFactory,
) : ConfigSource() {

    abstract val url: Property<String>

    override fun schema(context: ConfigSourceContext): Provider<List<ScopeModel>> =
        providers.of(Fetch::class.java) { it.parameters.url.set(url) }
            .map(ScopeSpecFormat::decode)

    abstract class Fetch : ValueSource<String, Fetch.Parameters> {
        interface Parameters : ValueSourceParameters {
            val url: Property<String>
        }

        override fun obtain(): String = URI(parameters.url.get()).toURL().readText()
    }
}
