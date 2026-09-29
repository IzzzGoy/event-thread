package ru.alexey.event.threads.test.graph

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventGraphFlowDslTest {

    private fun graphOf(vararg edges: Triple<String, String, String>, extraHandled: Set<ScopedEvent> = emptySet()) =
        EventGraph(
            handledEvents = edges.mapTo(mutableSetOf()) { (from, _, scope) -> ScopedEvent(scope, from) } + extraHandled,
            edges = edges.map { (from, to, scope) -> EventEdge(from, to, scope) }
        )

    @Test
    fun reachesWithOneEventPassesWhenHandled() {
        graphOf(Triple("AddTodo", "Pong", "S"))("S") {
            reaches("AddTodo")
        }
    }

    @Test
    fun reachesWithOneEventFailsWhenNotHandled() {
        val error = assertFailsWith<EventGraphValidationException> {
            graphOf(Triple("A", "B", "S"))("S") { reaches("Unhandled") }
        }
        assertTrue("Unhandled" in error.message.orEmpty())
    }

    @Test
    fun reachesWithAChainPassesWhenEveryHopIsADirectEdge() {
        graphOf(Triple("A", "B", "S"), Triple("B", "C", "S"))("S") {
            reaches("A", "B", "C")
        }
    }

    @Test
    fun reachesFailsAtTheSpecificBrokenHop() {
        // A->B is real, B->D is not (only B->C is) - the message should name the B->D hop, not just
        // "chain broken somewhere".
        val error = assertFailsWith<EventGraphValidationException> {
            graphOf(Triple("A", "B", "S"), Triple("B", "C", "S"))("S") {
                reaches("A", "B", "D")
            }
        }
        assertTrue("'B' -> 'D'" in error.message.orEmpty())
    }

    @Test
    fun reachesDoesNotAcceptATransitiveShortcutAsAHop() {
        // A->B->C is a real two-hop path, but A->C is not a *direct* edge - reaches() must reject
        // skipping straight from A to C even though C is transitively reachable from A.
        assertFailsWith<EventGraphValidationException> {
            graphOf(Triple("A", "B", "S"), Triple("B", "C", "S"))("S") {
                reaches("A", "C")
            }
        }
    }

    @Test
    fun doesNotReachPassesForAnUnhandledEvent() {
        graphOf(Triple("A", "B", "S"))("S") {
            doesNotReach("Nope")
        }
    }

    @Test
    fun doesNotReachFailsForAHandledEvent() {
        assertFailsWith<EventGraphValidationException> {
            graphOf(Triple("A", "B", "S"))("S") { doesNotReach("A") }
        }
    }

    @Test
    fun orphanPassesForAProducedButUnhandledEvent() {
        graphOf(Triple("A", "B", "S"))("S") {
            orphan("B")
        }
    }

    @Test
    fun orphanFailsWhenTheEventIsActuallyHandled() {
        assertFailsWith<EventGraphValidationException> {
            graphOf(Triple("A", "B", "S"), extraHandled = setOf(ScopedEvent("S", "B")))("S") {
                orphan("B")
            }
        }
    }

    @Test
    fun doesNotCascadePassesWhenNoDirectEdgeExists() {
        graphOf(Triple("A", "B", "S"))("S") {
            doesNotCascade("A", "C")
        }
    }

    @Test
    fun doesNotCascadeFailsWhenTheDirectEdgeExists() {
        assertFailsWith<EventGraphValidationException> {
            graphOf(Triple("A", "B", "S"))("S") { doesNotCascade("A", "B") }
        }
    }

    @Test
    fun assertionsInABlockAreQualifiedByTheGivenScopeOnly() {
        // "B" is handled in a different scope ("OTHER") - orphan-in-"S" must still hold.
        graphOf(Triple("A", "B", "S"), extraHandled = setOf(ScopedEvent("OTHER", "B")))("S") {
            reaches("A")
            orphan("B")
        }
    }
}
