package ru.alexey.event.threads.scopeholder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.alexey.event.threads.bus.StrictEvent
import kotlin.test.Test
import kotlin.test.assertEquals

data class RouteAEvent(val marker: String) : StrictEvent
data class RouteBEvent(val marker: String) : StrictEvent

class ExternalEventRoutingTest {

    @Test
    fun eventOnlyReachesTheScopeItWasActuallyMappedTo() = runTest {
        val receivedByA = mutableListOf<String>()
        val receivedByB = mutableListOf<String>()
        val gotIt = CompletableDeferred<Unit>()

        val holder = scopeHolder {
            RouteAEvent::class consume "ReceiverA"
            RouteBEvent::class consume "ReceiverB"

            scopeEmbedded("Source") {}

            scopeEmbedded("ReceiverA") {
                threads {
                    thread<RouteAEvent>().end {
                        receivedByA += it.marker
                        gotIt.complete(Unit)
                    }
                }
            }

            scopeEmbedded("ReceiverB") {
                threads {
                    // ReceiverB is only mapped to RouteBEvent, never RouteAEvent - if a
                    // RouteAEvent instance ends up here, the type filter regressed.
                    thread<RouteAEvent>().end {
                        receivedByB += it.marker
                    }
                }
            }
        }

        try {
            val source = holder.findOrLoad("Source")
            holder.findOrLoad("ReceiverA")
            holder.findOrLoad("ReceiverB")

            source + RouteAEvent("hello")
            gotIt.await()

            assertEquals(listOf("hello"), receivedByA)
            assertEquals(emptyList(), receivedByB)
        } finally {
            holder.close()
        }
    }

    @Test
    fun sourceScopeDoesNotReceiveItsOwnForwardedEvent() = runTest {
        val receivedBySource = mutableListOf<String>()
        val receivedByReceiver = mutableListOf<String>()
        val gotIt = CompletableDeferred<Unit>()

        val holder = scopeHolder {
            // Source is deliberately included in its own mapping's receivers, to exercise the
            // self-exclusion check rather than relying on it being absent by coincidence.
            RouteAEvent::class consume listOf("Source", "Receiver")

            scopeEmbedded("Source") {
                threads {
                    thread<RouteAEvent>().end { receivedBySource += it.marker }
                }
            }
            scopeEmbedded("Receiver") {
                threads {
                    thread<RouteAEvent>().end {
                        receivedByReceiver += it.marker
                        gotIt.complete(Unit)
                    }
                }
            }
        }

        try {
            val source = holder.findOrLoad("Source")
            holder.findOrLoad("Receiver")

            source + RouteAEvent("ping")
            gotIt.await()

            // Source's own thread<RouteAEvent>() fires once from the direct dispatch - the
            // forwarding pipeline must not deliver a second copy back to it.
            assertEquals(listOf("ping"), receivedBySource)
            assertEquals(listOf("ping"), receivedByReceiver)
        } finally {
            holder.close()
        }
    }

    @Test
    fun eventWithNoMatchingMappingIsNeverForwarded() = runTest {
        val receivedByOther = mutableListOf<String>()

        val holder = scopeHolder {
            RouteBEvent::class consume "Other"

            scopeEmbedded("Source") {}
            scopeEmbedded("Other") {
                threads {
                    thread<RouteAEvent>().end { receivedByOther += it.marker }
                }
            }
        }

        try {
            val source = holder.findOrLoad("Source")
            holder.findOrLoad("Other")

            source + RouteAEvent("unrouted")
            withContext(Dispatchers.Default) { delay(150) }

            assertEquals(emptyList(), receivedByOther)
        } finally {
            holder.close()
        }
    }
}
