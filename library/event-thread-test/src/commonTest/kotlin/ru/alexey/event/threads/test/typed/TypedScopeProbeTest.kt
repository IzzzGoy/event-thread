package ru.alexey.event.threads.test.typed

import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeholder.typed.DatacontainerTyped
import ru.alexey.event.threads.scopeholder.typed.EventHandler
import ru.alexey.event.threads.scopeholder.typed.ScopeKey
import ru.alexey.event.threads.scopeholder.typed.ScopeTyped
import ru.alexey.event.threads.scopeholder.typed.ScopeWiring
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Ping(val n: Int) : StrictEvent
private data class Pong(val n: Int) : StrictEvent

private object ProbeKey : ScopeKey<Unit> { override val identity = "Probe" }

private class Hits : DatacontainerTyped<Int>() { override val source = flowResource(0) }

private class ProbeScope(val hits: Hits = Hits()) : ScopeTyped<Unit>(ProbeKey, Unit) {
    override fun ScopeWiring.wire() {
        container("hits", hits)
        on(object : EventHandler.StateProcessor<Ping, Int>(hits) {
            override suspend fun process(event: Ping, state: Int) = state + event.n
        })
        on(object : EventHandler.EventCascader<Ping>() {
            override val outputs: Set<KClass<out Event>> = setOf(Pong::class)
            override fun cascade(input: Ping): List<Event> = listOf(Pong(input.n * 10))
        })
    }
}

private class Probe(val typed: ProbeScope = ProbeScope()) : TypedScopeProbe(typed) {
    val hits: Datacontainer<Int> get() = typed.hits
}

class TypedScopeProbeTest {

    @Test
    fun awaitsStateAndRecordsCascades() = runTest {
        val probe = Probe()

        probe.send(Ping(2))

        assertEquals(2, probe.await(probe.hits) { it == 2 })
        assertEquals(Pong(20), probe.awaitEmitted<Pong>())
        assertTrue(Ping(2) in probe.emitted.value)
        probe.close()
    }

    @Test
    fun awaitEmittedFiltersByPredicate() = runTest {
        val probe = Probe()

        probe.send(Ping(1))
        probe.send(Ping(3))

        assertEquals(Pong(30), probe.awaitEmitted<Pong> { it.n == 30 })
        probe.close()
    }

    @Test
    fun scenarioDslWorksOnTheProbesRecording() = runTest {
        val probe = Probe()

        probe.send(Ping(4))
        probe.awaitEmitted<Pong>()

        probe.scenario {
            expectEvents("Ping", "Pong")
            expectState(probe.hits, 4)
        }
        probe.close()
    }
}
