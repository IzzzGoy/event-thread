package ru.alexey.event.threads.test.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventGraphTest {

    private fun graphOf(vararg edges: Triple<String, String, String>, extraHandled: Set<ScopedEvent> = emptySet()) =
        EventGraph(
            handledEvents = edges.mapTo(mutableSetOf()) { (from, _, scope) -> ScopedEvent(scope, from) } + extraHandled,
            edges = edges.map { (from, to, scope) -> EventEdge(from, to, scope) }
        )

    @Test
    fun orphanEventsAreProducedButNeverListenedToInTheSameScope() {
        val graph = graphOf(Triple("A", "B", "S"))

        assertEquals(setOf(ScopedEvent("S", "B")), graph.orphanEvents())
    }

    @Test
    fun noOrphansWhenEveryProducedEventHasAHandlerInTheSameScope() {
        val graph = graphOf(Triple("A", "B", "S"), extraHandled = setOf(ScopedEvent("S", "B")))

        assertTrue(graph.orphanEvents().isEmpty())
    }

    @Test
    fun aHandlerInAnUnrelatedScopeDoesNotSuppressAnOrphanReport() {
        // "B" is handled in scope "OTHER", not in "S" where it's actually produced - cascade never
        // crosses scopes, so this must still be reported as an orphan in "S".
        val graph = graphOf(Triple("A", "B", "S"), extraHandled = setOf(ScopedEvent("OTHER", "B")))

        assertEquals(setOf(ScopedEvent("S", "B")), graph.orphanEvents())
    }

    @Test
    fun reachableFromFollowsCascadeEdgesTransitivelyWithinOneScope() {
        val graph = graphOf(
            Triple("A", "B", "S"),
            Triple("B", "C", "S"),
            Triple("B", "D", "S"),
        )

        assertEquals(setOf(ScopedEvent("S", "B"), ScopedEvent("S", "C"), ScopedEvent("S", "D")), graph.reachableFrom("S", "A"))
        assertEquals(setOf(ScopedEvent("S", "C"), ScopedEvent("S", "D")), graph.reachableFrom("S", "B"))
        assertTrue(graph.reachableFrom("S", "C").isEmpty())
    }

    @Test
    fun reachableFromDoesNotCrossIntoAnotherScopeWithTheSameEventName() {
        val graph = graphOf(Triple("A", "B", "S"), Triple("B", "C", "OTHER"))

        assertEquals(setOf(ScopedEvent("S", "B")), graph.reachableFrom("S", "A"))
    }

    @Test
    fun noCyclesInAPlainChain() {
        val graph = graphOf(Triple("A", "B", "S"), Triple("B", "C", "S"))

        assertTrue(graph.cycles().isEmpty())
    }

    @Test
    fun detectsAMultiNodeCycle() {
        val graph = graphOf(Triple("A", "B", "S"), Triple("B", "A", "S"))

        val cycles = graph.cycles()
        assertEquals(1, cycles.size)
        assertEquals(setOf(ScopedEvent("S", "A"), ScopedEvent("S", "B")), cycles.single().toSet())
    }

    @Test
    fun detectsASelfLoop() {
        val graph = graphOf(Triple("A", "A", "S"))

        assertEquals(listOf(listOf(ScopedEvent("S", "A"))), graph.cycles())
    }

    @Test
    fun disconnectedComponentsDontFalselyMergeIntoOneCycle() {
        // A->B->A is a real cycle; C->D is a separate, acyclic component - pins that the SCC walk
        // doesn't leak lowlink/index state across the two disjoint subgraphs.
        val graph = graphOf(
            Triple("A", "B", "S"),
            Triple("B", "A", "S"),
            Triple("C", "D", "S"),
        )

        val cycles = graph.cycles()
        assertEquals(1, cycles.size)
        assertEquals(setOf(ScopedEvent("S", "A"), ScopedEvent("S", "B")), cycles.single().toSet())
    }

    @Test
    fun sameEventNameInTwoScopesFormsTwoUnrelatedCycles() {
        val graph = graphOf(Triple("A", "B", "S1"), Triple("B", "A", "S1"), Triple("A", "B", "S2"), Triple("B", "A", "S2"))

        val cycles = graph.cycles()
        assertEquals(2, cycles.size)
        assertEquals(setOf("S1", "S2"), cycles.flatten().map { it.scope }.toSet())
    }

    @Test
    fun renderIncludesOrphansAndCycles() {
        val graph = graphOf(Triple("A", "B", "S"), Triple("B", "A", "S"), Triple("A", "C", "S"))

        val text = graph.render()

        assertTrue("Orphan" in text)
        assertTrue("C" in text)
        assertTrue("Cycles" in text)
    }

    @Test
    fun assertNoOrphanEventsPassesWhenNoneExist() {
        graphOf(Triple("A", "B", "S"), extraHandled = setOf(ScopedEvent("S", "B"))).assertNoOrphanEvents()
    }

    @Test
    fun assertNoOrphanEventsFailsWithTheOrphanInTheMessage() {
        val error = assertFailsWith<EventGraphValidationException> {
            graphOf(Triple("A", "B", "S")).assertNoOrphanEvents()
        }
        assertTrue("S::B" in error.message.orEmpty())
    }

    @Test
    fun assertNoCyclesFailsOnACycle() {
        assertFailsWith<EventGraphValidationException> {
            graphOf(Triple("A", "B", "S"), Triple("B", "A", "S")).assertNoCycles()
        }
    }

    @Test
    fun assertReachesPassesWhenEveryExpectedEventIsReachable() {
        graphOf(Triple("A", "B", "S"), Triple("B", "C", "S")).assertReaches("S", "A", "B", "C")
    }

    @Test
    fun assertReachesFailsWhenAnExpectedEventIsMissing() {
        val error = assertFailsWith<EventGraphValidationException> {
            graphOf(Triple("A", "B", "S")).assertReaches("S", "A", "B", "C")
        }
        assertTrue("C" in error.message.orEmpty())
    }

    @Test
    fun renderCascadeShowsEveryStepIndentedByDepth() {
        // C explicitly handled, not left produced-but-orphaned - isolates indentation from the
        // orphan marker, which has its own test below.
        val text = graphOf(
            Triple("A", "B", "S"), Triple("B", "C", "S"),
            extraHandled = setOf(ScopedEvent("S", "C"))
        ).renderCascade("S", "A")

        assertEquals(
            """
            Cascade from 'S::A':
            A
              -> B
                -> C

            """.trimIndent(),
            text
        )
    }

    @Test
    fun renderCascadeBranchesOnMultipleChildrenSortedByName() {
        val text = graphOf(
            Triple("A", "C", "S"), Triple("A", "B", "S"),
            extraHandled = setOf(ScopedEvent("S", "B"), ScopedEvent("S", "C"))
        ).renderCascade("S", "A")

        assertEquals(
            """
            Cascade from 'S::A':
            A
              -> B
              -> C

            """.trimIndent(),
            text
        )
    }

    @Test
    fun renderCascadeMarksAnOrphanLeaf() {
        val text = graphOf(Triple("A", "B", "S")).renderCascade("S", "A")

        assertTrue("B [orphan - no handler]" in text)
    }

    @Test
    fun renderCascadeStopsAtARepeatedNodeInsteadOfLoopingForever() {
        val text = graphOf(Triple("A", "B", "S"), Triple("B", "A", "S")).renderCascade("S", "A")

        assertEquals(
            """
            Cascade from 'S::A':
            A
              -> B
                -> A (see above)

            """.trimIndent(),
            text
        )
    }

    @Test
    fun renderCascadeOnALeafWithNoOutgoingEdgesIsJustTheRoot() {
        val text = graphOf(Triple("A", "B", "S"), extraHandled = setOf(ScopedEvent("S", "B"))).renderCascade("S", "B")

        assertEquals("Cascade from 'S::B':\nB\n", text)
    }
}
