package ru.alexey.event.threads.test.graph

/** Thrown by [EventGraph]'s `assert*` helpers - carries the failing check plus [EventGraph.render]
 * of the whole graph, so a test failure shows the full picture instead of just the one violation. */
class EventGraphValidationException(message: String) : IllegalStateException(message)

/** Fails if any `cascade` step in this graph dispatches an event its own scope has no
 * `thread<T>()` for - see [EventGraph.orphanEvents]. Framework-agnostic: throws a plain exception
 * rather than calling into `kotlin.test`, so it works the same from a `kotlin.test`/JUnit/Kotest
 * `@Test`, or even as a startup self-check in debug builds. */
fun EventGraph.assertNoOrphanEvents() {
    val orphans = orphanEvents()
    if (orphans.isNotEmpty()) {
        throw EventGraphValidationException(
            "Orphan event(s) - dispatched by a cascade step but never listened to in their own scope: " +
                "${orphans.map { it.toString() }.sorted()}\n\n${render()}"
        )
    }
}

/** Fails if this graph's produces-relation contains a structural cycle - see [EventGraph.cycles].
 * Only call this where a cycle genuinely shouldn't exist for the graph under test: some cycles are
 * legitimate (a convergent retry/backoff loop), so this is a deliberate opt-in check, not something
 * every event graph should be expected to pass. */
fun EventGraph.assertNoCycles() {
    val found = cycles()
    if (found.isNotEmpty()) {
        throw EventGraphValidationException(
            "Cycle(s) detected: ${found.joinToString { it.joinToString(" -> ") }}\n\n${render()}"
        )
    }
}

/** Fails unless every event in [expected] is reachable (transitively, via `cascade` edges) from
 * dispatching [event] on [scope] - see [EventGraph.reachableFrom]. Use to pin a chain's expected
 * branches so a later refactor that silently drops one is caught here instead of only at runtime. */
fun EventGraph.assertReaches(scope: String, event: String, vararg expected: String) {
    val reachable = reachableFrom(scope, event).map { it.event }.toSet()
    val missing = expected.filter { it !in reachable }
    if (missing.isNotEmpty()) {
        throw EventGraphValidationException(
            "Dispatching '$scope::$event' does not reach $missing (reachable: ${reachable.sorted()})\n\n${render()}"
        )
    }
}
