package ru.alexey.event.threads

import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeholder.typed.DatacontainerTyped
import ru.alexey.event.threads.scopeholder.typed.EventHandler
import ru.alexey.event.threads.scopeholder.typed.ScopeKey
import ru.alexey.event.threads.scopeholder.typed.ScopeTyped
import ru.alexey.event.threads.scopeholder.typed.ScopeWiring
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

private data object Tick : StrictEvent
private data object Alarm : StrictEvent

private object TallyKey : ScopeKey<Unit> { override val identity = "Tally" }

private class Ticks : DatacontainerTyped<Int>() { override val source = flowResource(0) }

/** Two handlers for one event - a tally, then a check - as a schema with several handlers
 * per event generates. */
private class TallyScope : ScopeTyped<Unit>(TallyKey, Unit) {
    override fun ScopeWiring.wire() {
        val ticks = Ticks()
        container("ticks", ticks)
        on(object : EventHandler.StateProcessor<Tick, Int>(ticks) {
            override suspend fun process(event: Tick, state: Int) = state + 1
        })
        on(object : EventHandler.EventCascader<Tick>() {
            override val outputs: Set<KClass<out Event>> = setOf(Alarm::class)
            override fun cascade(input: Tick): List<Event> = emptyList()
        })
        on(object : EventHandler.SideEffect<Alarm>() {
            override suspend fun handle(event: Alarm) = Unit
        })
    }
}

class MergedMetadataTest {

    @Test
    fun everyHandlerOfAnEventShowsInItsMetadata() {
        val scope = TallyScope()

        val tick = scope.metadata.eventsMetadata.getValue("Tick")

        assertEquals(listOf(EventType.modification, EventType.cascade), tick.actions.map { it.type })
        assertEquals(setOf("Alarm"), tick.producedTypes)
        assertEquals(setOf("Alarm"), tick.metadata.allowedOutputs)
        scope.close()
    }

    @Test
    fun severalDslThreadsForOneEventAllShow() {
        val scope = scopeBuilder("Dsl") {
            threads {
                thread<Tick>().end { }
                thread<Tick>().then { Alarm }
            }
        }(emptyMap()).build()

        assertEquals(listOf(EventType.consume, EventType.cascade), scope.metadata.eventsMetadata.getValue("Tick").actions.map { it.type })
        scope.close()
    }
}
