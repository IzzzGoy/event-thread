package ru.alexey.event.threads.test.graph

/**
 * Human-readable flow assertions scoped to one [scope] of an [EventGraph] - built via
 * `graph("ScopeName") { }` (see [invoke] below), where `graph` is the [EventGraph] under test.
 * Every verb here takes bare event names (already implicitly qualified by [scope], so no manual
 * [ScopedEvent]/[EventEdge] construction at call sites) and fails immediately - throwing
 * [EventGraphValidationException] - at the exact hop that doesn't hold, instead of a single
 * deferred boolean over the whole chain. If you're asserting a flow has a specific shape, the
 * assertion should read like that shape: `reaches("AddTodo", "Pong", "Done")` is the literal path,
 * not an unordered "is this set of events somewhere downstream" check (see [EventGraph.
 * reachableFrom]/[assertReaches] for that looser, order-independent alternative).
 */
class ScopeFlow(private val graph: EventGraph, private val scope: String) {

    /**
     * One event: asserts it's handled in [scope] - has a `thread<T>()` there, i.e. it's a real,
     * live node in this scope's graph (`ScopedEvent(scope, event) in graph.handledEvents`).
     *
     * More than one event: asserts the *literal* hop-by-hop chain - every consecutive pair must be
     * a direct `cascade` edge in [scope] (not merely eventually reachable through some other,
     * unlisted path). A broken link fails right at that specific hop, naming both events involved,
     * instead of reporting the whole chain as vaguely "not reached".
     */
    fun reaches(vararg path: String) {
        require(path.isNotEmpty()) { "reaches() needs at least one event" }

        val entry = ScopedEvent(scope, path[0])
        if (entry !in graph.handledEvents) {
            throw EventGraphValidationException(
                "Expected '$scope' to handle '${path[0]}' (thread<T>()), but it doesn't.\n\n${graph.render()}"
            )
        }

        for (i in 0 until path.size - 1) {
            val from = path[i]
            val to = path[i + 1]
            if (EventEdge(from, to, scope) !in graph.edges) {
                throw EventGraphValidationException(
                    "Expected '$scope' to cascade '$from' -> '$to' directly, but no such edge exists " +
                        "(full expected chain: ${path.joinToString(" -> ")}).\n\n${graph.render()}"
                )
            }
        }
    }

    /** The negation of a single-event [reaches]: asserts [event] has no `thread<T>()` in [scope]
     * at all - regardless of whether anything produces it (for "produced, but specifically
     * unhandled", see [orphan] instead, which is a stricter, more specific claim). */
    fun doesNotReach(event: String) {
        val entry = ScopedEvent(scope, event)
        if (entry in graph.handledEvents) {
            throw EventGraphValidationException(
                "Expected '$scope' to NOT handle '$event', but it does.\n\n${graph.render()}"
            )
        }
    }

    /** Asserts each of [events] is dispatched by a `cascade` step in [scope] but has no
     * `thread<T>()` of its own there - see [EventGraph.orphanEvents]. Stricter than [doesNotReach]:
     * this additionally requires the event to actually be produced by something in [scope]. */
    fun orphan(vararg events: String) {
        val orphaned = graph.orphanEvents().filterTo(mutableSetOf()) { it.scope == scope }.map { it.event }
        val notOrphaned = events.filterNot { it in orphaned }
        if (notOrphaned.isNotEmpty()) {
            throw EventGraphValidationException(
                "Expected $notOrphaned to be orphaned in '$scope' (produced there, but unhandled) - they aren't.\n\n${graph.render()}"
            )
        }
    }

    /** Asserts no direct `cascade` edge exists from [from] to [to] in [scope] - the negation of a
     * `reaches` hop, for pinning that two events are deliberately *not* directly connected (e.g. a
     * known gap: an event dispatched imperatively rather than via a cascading `.then { }`, which
     * this graph can't see - see [EventGraph.edges]). */
    fun doesNotCascade(from: String, to: String) {
        if (EventEdge(from, to, scope) in graph.edges) {
            throw EventGraphValidationException(
                "Expected '$scope' to NOT cascade '$from' -> '$to' directly, but it does.\n\n${graph.render()}"
            )
        }
    }

    /** [EventGraph.renderCascade] for [event] on this block's [scope] - a quick way to eyeball
     * what a flow actually looks like (e.g. `println(cascadeOf("AddTodo"))`) while writing or
     * debugging a `reaches(...)`/`doesNotCascade(...)` assertion above. */
    fun cascadeOf(event: String): String = graph.renderCascade(scope, event)
}

/** Entry point for the human-readable flow DSL: `graph("ScopeName") { reaches("AddTodo") }`, where
 * `graph` is the [EventGraph] under test - see [ScopeFlow] for every available verb. */
operator fun EventGraph.invoke(scope: String, block: ScopeFlow.() -> Unit) {
    ScopeFlow(this, scope).apply(block)
}
