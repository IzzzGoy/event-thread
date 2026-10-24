package ru.alexey.event.threads.test.graph

import ru.alexey.event.threads.ScopeMetadata
import ru.alexey.event.threads.scopeholder.ScopeHolderMetadata

/** Builds an [EventGraph] from one [ScopeMetadata] snapshot (see [ru.alexey.event.threads.Scope.
 * metadata]) - every `cascade` step becomes an [EventEdge] qualified by [scopeName]. */
fun ScopeMetadata.toEventGraph(scopeName: String = "self"): EventGraph {
    val edges = eventsMetadata.flatMap { (event, info) ->
        info.producedTypes.map { produced -> EventEdge(from = event, to = produced, scope = scopeName) }
    }
    val handled = eventsMetadata.keys.mapTo(mutableSetOf()) { ScopedEvent(scopeName, it) }
    return EventGraph(handledEvents = handled, edges = edges)
}

/** Builds an [EventGraph] spanning every scope in a [ScopeHolderMetadata] snapshot (see
 * [ru.alexey.event.threads.scopeholder.generateStaticSchema]/[ru.alexey.event.threads.scopeholder.
 * generateActiveSchema]) - the combined picture of what every currently active scope in this
 * holder could dispatch as a consequence of its own events. Each scope's subgraph stays isolated
 * (see [ScopedEvent]) since `.then { }` can't cascade across scopes. */
fun ScopeHolderMetadata.toEventGraph(): EventGraph {
    val edges = scopesMetadata.flatMap { scope ->
        scope.events.flatMap { event ->
            event.info.producedTypes.map { produced ->
                EventEdge(from = event.name, to = produced, scope = scope.name)
            }
        }
    }
    val handled = scopesMetadata.flatMapTo(mutableSetOf()) { scope ->
        scope.events.map { ScopedEvent(scope.name, it.name) }
    }
    return EventGraph(handledEvents = handled, edges = edges)
}
