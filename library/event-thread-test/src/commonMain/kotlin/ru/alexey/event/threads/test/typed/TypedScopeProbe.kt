package ru.alexey.event.threads.test.typed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.bus.Event
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Test harness around one live [Scope] - the base of the generated `<Name>ScopeProbe`s, which add
 * typed access to the scope's containers. Records everything the scope's bus processes from the
 * moment the probe is built ([emitted]: sent events and everything they cascade into), and waits
 * for state in real time ([await]): the bus dispatches on real dispatchers, which `runTest`'s
 * virtual clock doesn't drive.
 */
abstract class TypedScopeProbe(val scope: Scope) : AutoCloseable {

    private val recorder = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val recorded = MutableStateFlow<List<Event>>(emptyList())

    /** Every event the scope processed since the probe was built, in processing order. */
    val emitted: StateFlow<List<Event>> = recorded.asStateFlow()

    init {
        // UNDISPATCHED: subscribed before the constructor returns, so the first send is recorded.
        // (Touching eventBus also wires the scope.)
        recorder.launch(start = CoroutineStart.UNDISPATCHED) {
            scope.eventBus.output.collect { event -> recorded.update { it + event } }
        }
    }

    /** Dispatches [event] into the scope. */
    fun send(event: Event) {
        scope + event
    }

    /** Waits until [state]'s value satisfies [until], and returns it. */
    suspend fun <T> await(state: StateFlow<T>, timeout: Duration = DEFAULT_TIMEOUT, until: (T) -> Boolean): T =
        realTime(timeout) { state.first(until) }

    /** Waits until an [E] satisfying [until] has been processed by the scope, and returns it. */
    suspend inline fun <reified E : Event> awaitEmitted(
        timeout: Duration = DEFAULT_TIMEOUT,
        noinline until: (E) -> Boolean = { true },
    ): E = awaitEmitted(E::class, timeout, until)

    @PublishedApi
    internal suspend fun <E : Event> awaitEmitted(type: kotlin.reflect.KClass<E>, timeout: Duration, until: (E) -> Boolean): E =
        realTime(timeout) {
            @Suppress("UNCHECKED_CAST")
            emitted.first { events -> events.any { type.isInstance(it) && until(it as E) } }
                .first { type.isInstance(it) && until(it as E) } as E
        }

    private suspend fun <T> realTime(timeout: Duration, block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(timeout) { block() } }

    override fun close() {
        recorder.cancel()
        scope.close()
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 5.seconds
    }
}
