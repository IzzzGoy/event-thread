package ru.alexey.event.threads.scopeholder.typed

import ru.alexey.event.threads.bus.Event
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
}
