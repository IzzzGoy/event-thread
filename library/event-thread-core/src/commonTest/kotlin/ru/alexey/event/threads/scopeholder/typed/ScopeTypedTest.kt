package ru.alexey.event.threads.scopeholder.typed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.EventType
import ru.alexey.event.threads.Privacy
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.EventBus
import ru.alexey.event.threads.bus.EventBusBuilder
import ru.alexey.event.threads.bus.OutputContractViolationException
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.resources.flowResource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class Increment(val by: Int) : StrictEvent
private data object Ping : StrictEvent

private class CountContainer : DatacontainerTyped<Int>() {
    override val source = flowResource(0)
}

private class IncrementProcessor(count: CountContainer) : EventHandler.StateProcessor<Increment, Int>(count) {
    override suspend fun process(event: Increment, state: Int) = state + event.by
}

private class PingCascader : EventHandler.EventCascader<Ping>() {
    override val outputs = setOf(Increment::class)
    override fun cascade(input: Ping): List<Event> = listOf(Increment(10))
}

/** Declares Increment but returns Ping - an output-contract violation. */
private class LyingCascader : EventHandler.EventCascader<Ping>() {
    override val outputs = setOf(Increment::class)
    override val privacy = Privacy.private
    override fun cascade(input: Ping): List<Event> = listOf(Ping)
}

private object CountKey : ScopeKey<Unit>

/** Constructor-injected, the shape generated scopes take. */
private class CountScope(
    private val count: CountContainer,
    private val increment: EventHandler.StateProcessor<Increment, Int>,
    private val ping: EventHandler.EventCascader<Ping>,
    eventBusFactory: () -> EventBus = EventBus::defaultFactory,
) : ScopeTyped<Unit>(CountKey, Unit, eventBusFactory = eventBusFactory) {
    override fun ScopeWiring.wire() {
        container("count", count)
        on(increment)
        on(ping)
    }
}

class ScopeTypedTest {

    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5000) { block() } }

    @Test
    fun injectedCascaderDispatchesIntoItsScope() = runTest {
        val count = CountContainer()
        val scope = CountScope(count, IncrementProcessor(count), PingCascader())

        scope + Ping

        assertEquals(10, awaitReal { scope.resolveOrThrow<Int>("count").first { it == 10 } })
        scope.close()
    }

    @Test
    fun processorWithAnotherContainerInstanceFailsOnFirstUse() {
        val scope = CountScope(CountContainer(), IncrementProcessor(CountContainer()), PingCascader())

        val error = assertFailsWith<IllegalStateException> { scope.eventBus }

        assertContains(error.message.orEmpty(), "IncrementProcessor updates a container that isn't registered on this scope")
    }

    @Test
    fun cascaderCanBelongToOneScopeOnly() {
        val shared = PingCascader()
        val first = CountContainer().let { CountScope(it, IncrementProcessor(it), shared) }
        val second = CountContainer().let { CountScope(it, IncrementProcessor(it), shared) }
        first.eventBus

        val error = assertFailsWith<IllegalStateException> { second.eventBus }

        assertContains(error.message.orEmpty(), "already wired into another scope")
        first.close()
    }

    @Test
    fun handlerMetadataMatchesTheDsl() {
        val count = CountContainer()
        val scope = CountScope(count, IncrementProcessor(count), LyingCascader())

        val threads = scope.metadata.eventsMetadata
        val ping = threads.getValue("Ping")

        assertEquals("LyingCascader", ping.metadata.description)
        assertEquals(Privacy.private, ping.metadata.privacy)
        assertEquals(setOf("Increment"), ping.metadata.allowedOutputs)
        assertEquals(EventType.cascade, ping.actions.single().type)
        assertEquals(setOf("Increment"), ping.producedTypes)
        assertEquals(Privacy.public, threads.getValue("Increment").metadata.privacy)
        assertEquals(emptySet(), threads.getValue("Increment").producedTypes)
        scope.close()
    }

    @Test
    fun undeclaredCascadeOutputIsReportedAndNotDelivered() = runTest {
        val errors = MutableStateFlow<List<Throwable>>(emptyList())
        val count = CountContainer()
        val scope = CountScope(
            count, IncrementProcessor(count), LyingCascader(),
            eventBusFactory = { EventBusBuilder().apply { onError { _, e -> errors.update { it + e } } }.build() },
        )

        scope + Ping

        val reported = awaitReal { errors.first { it.isNotEmpty() } }.single()
        assertTrue(reported is OutputContractViolationException, "got $reported")
        assertEquals(0, count.value)
        scope.close()
    }

    @Test
    fun unwiredCascaderFailsClearly() = runTest {
        val error = assertFailsWith<IllegalStateException> { PingCascader().handle(Ping) }

        assertContains(error.message.orEmpty(), "isn't wired into a scope")
    }
}
