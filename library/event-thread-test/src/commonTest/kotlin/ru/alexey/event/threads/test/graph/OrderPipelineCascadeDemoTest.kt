package ru.alexey.event.threads.test.graph

import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.generateActiveSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Demonstrates the cascade-checking tools ([EventGraph], the `graph("Scope") { }` DSL,
 * [EventGraph.renderCascade]) against [orderPipelineHolder]'s real 3-level-deep, branching
 * cascade - see that file's KDoc for the exact shape being asserted against here.
 */
class OrderPipelineCascadeDemoTest {

    private fun loadedGraph(): Pair<ScopeHolder, EventGraph> {
        val holder = orderPipelineHolder()
        holder.findOrLoad("OrderPipeline")
        return holder to holder.generateActiveSchema().toEventGraph()
    }

    @Test
    fun theWholeThreeHopChainIsReachableHopByHop() {
        val (holder, graph) = loadedGraph()
        try {
            // reaches() checks every consecutive pair is a *direct* cascade edge - this is the
            // literal depth-0 -> depth-1 -> depth-2 -> depth-3 path down branch A.
            graph("OrderPipeline") {
                reaches("OrderPlaced", "OrderValidated", "PaymentCharged", "OrderShipped")
            }
        } finally {
            holder.free("OrderPipeline")
        }
    }

    @Test
    fun theOtherBranchAtEachLevelIsReachableToo() {
        val (holder, graph) = loadedGraph()
        try {
            // OrderValidated fans out to two events (branch A and branch B) - both are direct
            // hops, independently of which one theWholeThreeHopChainIsReachableHopByHop follows.
            graph("OrderPipeline") {
                reaches("OrderValidated", "InventoryReserved")
            }
        } finally {
            holder.free("OrderPipeline")
        }
    }

    @Test
    fun reachesRejectsSkippingAHop() {
        val (holder, graph) = loadedGraph()
        try {
            // OrderPlaced does reach PaymentCharged transitively (via OrderValidated), but not
            // *directly* - reaches() is about the literal chain shape, not "reachable eventually".
            val error = assertFailsWith<EventGraphValidationException> {
                graph("OrderPipeline") { reaches("OrderPlaced", "PaymentCharged") }
            }
            assertTrue("'OrderPlaced' -> 'PaymentCharged'" in error.message.orEmpty())
        } finally {
            holder.free("OrderPipeline")
        }
    }

    @Test
    fun theDeepOrphanThreeLevelsDownIsCaught() {
        val (holder, graph) = loadedGraph()
        try {
            // ReceiptEmailQueued is produced at depth 3 (PaymentCharged -> ReceiptEmailQueued)
            // but never has a thread<T>() registered for it anywhere in "OrderPipeline" - orphan
            // detection isn't limited to a cascade's immediate output, it walks the whole graph.
            graph("OrderPipeline") {
                orphan("ReceiptEmailQueued")
                doesNotReach("ReceiptEmailQueued")
            }
        } finally {
            holder.free("OrderPipeline")
        }
    }

    @Test
    fun everyDownstreamEventIsTransitivelyReachableFromTheRoot() {
        val (holder, graph) = loadedGraph()
        try {
            assertEquals(
                setOf("OrderValidated", "PaymentCharged", "InventoryReserved", "OrderShipped", "ReceiptEmailQueued")
                    .mapTo(mutableSetOf()) { ScopedEvent("OrderPipeline", it) },
                graph.reachableFrom("OrderPipeline", "OrderPlaced")
            )
        } finally {
            holder.free("OrderPipeline")
        }
    }

    @Test
    fun thePipelineHasNoCycles() {
        val (holder, graph) = loadedGraph()
        try {
            graph.assertNoCycles()
        } finally {
            holder.free("OrderPipeline")
        }
    }

    @Test
    fun renderCascadePrintsTheWholeTreeWithTheOrphanMarked() {
        val (holder, graph) = loadedGraph()
        try {
            val text = graph.renderCascade("OrderPipeline", "OrderPlaced")
            println(text)

            assertEquals(
                """
                Cascade from 'OrderPipeline::OrderPlaced':
                OrderPlaced
                  -> OrderValidated
                    -> InventoryReserved
                    -> PaymentCharged
                      -> OrderShipped
                      -> ReceiptEmailQueued [orphan - no handler]

                """.trimIndent(),
                text
            )
        } finally {
            holder.free("OrderPipeline")
        }
    }
}
