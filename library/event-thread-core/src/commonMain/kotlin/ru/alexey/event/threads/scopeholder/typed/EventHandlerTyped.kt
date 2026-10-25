package ru.alexey.event.threads.scopeholder.typed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.EventSequence
import ru.alexey.event.threads.bus.IEventBus
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.OutputMismatchBehavior
import ru.alexey.event.threads.Privacy
import kotlin.concurrent.Volatile
import kotlin.reflect.KClass


sealed interface EventHandler<Event> {
    suspend fun handle(event: Event)

    /** Registration metadata the DSL sets via `thread<T> { privacy(...) }` - informational. */
    val privacy: Privacy get() = Privacy.public

    /** `true`: replaces another registration for the same event on the scope's bus instead of
     * running alongside it - same as `thread<T> { override(true) }`. */
    val override: Boolean get() = false

    abstract class StateProcessor<T : Event, S : Any>(
        // internal, not private: ScopeWiring checks it's the scope's own container instance.
        internal val datacontainer: Datacontainer<S>
    ) : EventHandler<T> {

        protected abstract suspend fun process(event: T, state: S): S

        override suspend fun handle(event: T) {
            try {
                datacontainer.update { currentState ->
                    process(event = event, state = currentState)
                }
            } catch (e: Exception) {
                onError(event, e)
                throw e
            }
        }

        protected open fun onError(event: T, error: Exception) {}
    }

    abstract class SideEffect<E : Event> : EventHandler<E>

    /** Cascades into the bus of the scope it's wired into - attached by [ScopeWiring.on], so a
     * cascader can be built (and injected) before its scope, which owns the bus, exists. */
    abstract class EventCascader<E : Event> : EventHandler<E> {

        @Volatile
        private var eventBus: IEventBus? = null

        /** Every event type [cascade] may return - the `thread<T> { output<X>() }` of the DSL.
         * Required because it can't be inferred: [cascade]'s `List<Event>` erases it. Feeds the
         * event-flow graph and is enforced on dispatch (see [onMismatch]); an empty set means
         * unrestricted, as with no `output<>()` in the DSL. */
        abstract val outputs: Set<KClass<out Event>>

        /** What happens when [cascade] returns an event outside [outputs]. */
        open val onMismatch: OutputMismatchBehavior get() = OutputMismatchBehavior.Strict

        protected abstract fun cascade(input: E): List<Event>

        /** The events [cascade] returns for [input], without dispatching them - for tests and
         * inspection (a cascader needn't be wired into a scope for this). */
        fun preview(input: E): List<Event> = cascade(input)

        internal fun attach(bus: IEventBus) {
            val current = eventBus
            check(current == null || current === bus) {
                "${this::class.simpleName} is already wired into another scope - give each scope its own instance"
            }
            eventBus = bus
        }

        override suspend fun handle(event: E) {
            val bus = checkNotNull(eventBus) { "${this::class.simpleName} isn't wired into a scope" }
            cascadeInto(event, bus)
        }

        /** [ScopeWiring]'s dispatch path - [bus] is the scope's, wrapped to enforce [outputs]. */
        internal fun cascadeInto(event: E, bus: IEventBus) {
            cascade(event).forEach { bus += it }
        }
    }

    /**
     * Calls something remote on each [E] and tracks it in a [RemoteData] container: `Loading`
     * (keeping the last value), then `Success` or `Failure` - and cascades [onSuccess]/[onFailure]'s
     * events. [policy] decides what a trigger does while a request runs (cancel it, be ignored, or
     * queue), retries, timeouts and debouncing.
     *
     * Requests run in the handler's own coroutine scope, not in the bus's dispatch: a slow call
     * never holds up the scope's other events. Closed with the scope (a running call is cancelled).
     */
    abstract class Request<E : Event, T : Any>(
        // internal, not private: ScopeWiring checks it's the scope's own container instance.
        internal val datacontainer: Datacontainer<RemoteData<T>>,
    ) : EventHandler<E>, AutoCloseable {

        /** The remote call. Throwing is a failure; it's retried per [policy]. */
        protected abstract suspend fun call(event: E): T

        /** Events to cascade after a successful call. */
        protected open fun onSuccess(event: E, result: T): List<Event> = emptyList()

        /** Events to cascade after a failed call (all attempts used). */
        protected open fun onFailure(event: E, error: Throwable): List<Event> = emptyList()

        /** Every event type [onSuccess]/[onFailure] may return - enforced, as a cascader's. */
        open val outputs: Set<KClass<out Event>> get() = emptySet()

        open val onMismatch: OutputMismatchBehavior get() = OutputMismatchBehavior.Strict

        open val policy: RequestPolicy get() = RequestPolicy()

        @Volatile
        private var dispatch: ((E, List<Event>) -> Unit)? = null
        private val requests = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Guarded by jobLock:
        private val jobLock = Mutex()
        private var current: Job? = null
        private var newest = Long.MIN_VALUE
        private val pending = mutableListOf<Pair<Long, E>>()
        private var worker: Job? = null

        internal fun attach(dispatch: (E, List<Event>) -> Unit) {
            check(this.dispatch == null) {
                "${this::class.simpleName} is already wired into another scope - give each scope its own instance"
            }
            this.dispatch = dispatch
        }

        override suspend fun handle(event: E) {
            val dispatch = checkNotNull(dispatch) { "${this::class.simpleName} isn't wired into a scope" }
            val policy = policy
            // The bus's receipt order: events are dispatched concurrently, so triggers sent in a
            // burst can arrive here out of order. null: routed in from another scope - unordered.
            val sequence = currentCoroutineContext()[EventSequence]?.value
            val run: suspend (E) -> Unit = { trigger ->
                if (policy.debounceMs > 0) delay(policy.debounceMs)
                dispatch(trigger, execute(trigger))
            }
            jobLock.withLock {
                when (policy.concurrency) {
                    RequestPolicy.Concurrency.Latest -> {
                        if (sequence != null) {
                            // An older trigger arriving late must not replace a newer one.
                            if (sequence < newest) return
                            newest = sequence
                        }
                        current?.cancel()
                        current = requests.launch { run(event) }
                    }
                    RequestPolicy.Concurrency.Drop -> {
                        if (current?.isActive != true) current = requests.launch { run(event) }
                    }
                    RequestPolicy.Concurrency.Queue -> {
                        pending += (sequence ?: Long.MAX_VALUE) to event
                        pending.sortBy { it.first }
                        if (worker?.isActive != true) worker = requests.launch { drain(run) }
                    }
                }
            }
        }

        /** Runs queued triggers one at a time, in receipt order. */
        private suspend fun drain(run: suspend (E) -> Unit) {
            while (true) {
                val next = jobLock.withLock {
                    pending.removeFirstOrNull() ?: run { worker = null; null }
                } ?: return
                run(next.second)
            }
        }

        /**
         * One request for [event], to completion - retries and timeout applied, the container
         * updated - returning the events to cascade without dispatching them. What [handle] runs
         * (without the concurrency policy); for tests, and the generated examples.
         */
        suspend fun execute(event: E): List<Event> {
            datacontainer.update { RemoteData.Loading(it.valueOrNull) }
            val result = try {
                attempt(event)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                datacontainer.update { RemoteData.Failure(t, it.valueOrNull) }
                return onFailure(event, t)
            }
            datacontainer.update { RemoteData.Success(result) }
            return onSuccess(event, result)
        }

        private suspend fun attempt(event: E): T {
            val policy = policy
            var delayMs = policy.retryDelayMs
            var attempt = 0
            while (true) {
                try {
                    val timeout = policy.timeoutMs ?: return call(event)
                    return try {
                        withTimeout(timeout) { call(event) }
                    } catch (e: TimeoutCancellationException) {
                        throw RequestTimeoutException(timeout)
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    if (attempt++ >= policy.retry) throw t
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }

        /** Cancels a running request. */
        override fun close() {
            requests.cancel()
        }
    }
}
