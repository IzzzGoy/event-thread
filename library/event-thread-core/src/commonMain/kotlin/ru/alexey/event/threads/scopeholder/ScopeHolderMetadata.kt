package ru.alexey.event.threads.scopeholder

import kotlinx.serialization.Serializable
import ru.alexey.event.threads.EventThreadInfo

/** Serializable introspection snapshot of a [ScopeHolder] - its `dependsOn` graph
 * ([externalDependencies]), `consume` routing ([consumedMetadata]), and per-scope metadata
 * ([scopesMetadata]). Build one with [generateStaticSchema]/[generateActiveSchema]; ships to a
 * debug/inspector tool rather than being consumed by the library itself. */
@Serializable
data class ScopeHolderMetadata(
    val externalDependencies: List<ExternalDependencyMetadata>,
    val scopesMetadata: List<ScopeMetadata>,
    val consumedMetadata: List<ConsumedMetadata>,
)

@Serializable
data class ExternalDependencyMetadata(
    val scope: String,
    val dependencies: List<String>,
)

@Serializable
data class ScopeMetadata(
    val name: String,
    val description: String,
    val events: List<EventInfo>,
)

@Serializable
data class EventInfo(
    val name: String,
    val info: EventThreadInfo,
)

@Serializable
data class ConsumedMetadata(
    val event: String,
    val scopes: List<String>,
)

/**
 * Builds a [ScopeHolderMetadata] snapshot of this holder's declared `dependsOn`/`consume`
 * configuration and its currently active scopes' metadata.
 *
 * Despite the name, this currently returns the same result as [generateActiveSchema]: both read
 * per-scope metadata from [ScopeHolder.activeMetadata], which only covers scopes that are
 * actually loaded. A true "static" schema - covering every scope declared in the
 * [ru.alexey.event.threads.scopeholder.ScopeHolderBuilder], loaded or not - isn't implemented
 * yet, since producing one would mean instantiating every declared scope just to introspect it.
 */
fun ScopeHolder.generateStaticSchema(): ScopeHolderMetadata {
    return ScopeHolderMetadata(
        externalDependencies = dependencies.map { (scope, deps) ->
            ExternalDependencyMetadata(scope, deps)
        },
        consumedMetadata = external.map { (k, v) ->
            ConsumedMetadata(k.simpleName.orEmpty(), v)
        },
        scopesMetadata = activeMetadata.map { (scope, metadata) ->
            ScopeMetadata(
                name = scope,
                description = metadata.description,
                events = metadata.eventsMetadata.map { (event, metadata) ->
                    EventInfo(
                        name = event,
                        info = metadata,
                    )
                }
            )
        }
    )
}

/** Builds a [ScopeHolderMetadata] snapshot of this holder's declared `dependsOn`/`consume`
 * configuration and its currently active scopes' metadata (see [ScopeHolder.activeMetadata]). */
fun ScopeHolder.generateActiveSchema(): ScopeHolderMetadata {
    return ScopeHolderMetadata(
        externalDependencies = dependencies.map { (scope, deps) ->
            ExternalDependencyMetadata(scope, deps)
        },
        consumedMetadata = external.map { (k, v) ->
            ConsumedMetadata(k.simpleName.orEmpty(), v)
        },
        scopesMetadata = activeMetadata.map { (scope, metadata) ->
            ScopeMetadata(
                name = scope,
                description = metadata.description,
                events = metadata.eventsMetadata.map { (event, metadata) ->
                    EventInfo(
                        name = event,
                        info = metadata,
                    )
                }
            )
        }
    )
}