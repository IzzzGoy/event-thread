package ru.alexey.event.threads.test.graph

import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.scopeholder.generateActiveSchema
import ru.alexey.event.threads.scopeholder.scopeHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Ping(val n: Int) : StrictEvent
private data class Pong(val n: Int) : StrictEvent

class EventGraphIntegrationTest {

    // End-to-end check that the `reified OTHER` capture added to `Scope.then` actually reaches
    // the graph: a real `.then { }` cascade should show up as a real EventGraph edge, not just in
    // the EventThreadInfo it's built from.
    @Test
    fun cascadeThenIsCapturedAsAnEdgeWithNoOrphans() {
        val holder = scopeHolder {
            scopeEmbedded("Chat") {
                threads {
                    thread<Ping>().then { Pong(it.n) }
                    thread<Pong>().end { }
                }
            }
        }
        holder.findOrLoad("Chat")

        val graph = holder.generateActiveSchema().toEventGraph()

        assertTrue(EventEdge("Ping", "Pong", "Chat") in graph.edges)
        assertTrue(graph.orphanEvents().isEmpty())
        assertEquals(setOf(ScopedEvent("Chat", "Pong")), graph.reachableFrom("Chat", "Ping"))

        holder.free("Chat")
    }

    @Test
    fun cascadeToAnUnhandledEventIsReportedAsAnOrphan() {
        val holder = scopeHolder {
            scopeEmbedded("Chat") {
                threads {
                    thread<Ping>().then { Pong(it.n) }
                }
            }
        }
        holder.findOrLoad("Chat")

        val graph = holder.generateActiveSchema().toEventGraph()

        assertEquals(setOf(ScopedEvent("Chat", "Pong")), graph.orphanEvents())

        holder.free("Chat")
    }

    @Test
    fun mutualCascadeBetweenTwoThreadsIsDetectedAsACycle() {
        val holder = scopeHolder {
            scopeEmbedded("Chat") {
                threads {
                    thread<Ping>().then { Pong(it.n) }
                    thread<Pong>().then { Ping(it.n) }
                }
            }
        }
        holder.findOrLoad("Chat")

        val graph = holder.generateActiveSchema().toEventGraph()

        val cycles = graph.cycles()
        assertEquals(1, cycles.size)
        assertEquals(setOf(ScopedEvent("Chat", "Ping"), ScopedEvent("Chat", "Pong")), cycles.single().toSet())

        holder.free("Chat")
    }

    @Test
    fun aHandlerInADifferentScopeDoesNotHideAnOrphanInThisOne() {
        val holder = scopeHolder {
            scopeEmbedded("Sender") {
                threads {
                    thread<Ping>().then { Pong(it.n) }
                }
            }
            scopeEmbedded("Receiver") {
                threads {
                    thread<Pong>().end { }
                }
            }
        }
        holder.findOrLoad("Sender")
        holder.findOrLoad("Receiver")

        val graph = holder.generateActiveSchema().toEventGraph()

        // "Receiver" handling Pong must not suppress "Sender"'s own orphaned cascade to Pong -
        // Scope.then dispatches onto its own EventBus only, it never reaches another scope's bus.
        assertEquals(setOf(ScopedEvent("Sender", "Pong")), graph.orphanEvents())

        holder.free("Sender")
        holder.free("Receiver")
    }
}
