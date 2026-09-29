package ru.alexey.event.threads.scopeholder

import kotlinx.serialization.Serializable
import ru.alexey.event.threads.EventThreadInfo

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