package ru.alexey.event.threads.emitter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.datacontainer.Datacontainer

/** Receiver for `emitters { }` (see [ru.alexey.event.threads.ScopeBuilder.emitters]) - registers
 * external event sources that get collected into a scope's bus once it's built. */
class EmittersBuilder {
    private val emitterFactories = mutableListOf<Scope.() -> Emitter<out Event>>()

    /** Instantiates every registered emitter against [scope] and wires each one's [Emitter.flow]
     * into `scope.eventBus` via [ru.alexey.event.threads.bus.EventBus.collectToEventBus]. Called
     * once, from [ru.alexey.event.threads.Scope]'s own construction. */
    fun build(scope: Scope) = emitterFactories.map {
        with(scope) {
            it().also { eventBus.collectToEventBus(it.flow) }
        }
    }

    /** Registers an emitter, built lazily from the owning [Scope] when [build] runs. */
    fun <T : Event> emitter(block: Scope.() -> Emitter<T>) {
        emitterFactories.add(block)
    }

    /** Copies [other]'s registered emitters into this builder - used when a scope `implements` another. */
    fun merge(other: EmittersBuilder) {
        emitterFactories += other.emitterFactories
    }

    /** Wraps a plain [Flow] as an [Emitter] - the usual way to define one: `emitter {
     * wrapFlow(someFlow) }`. */
    fun <T : Event> wrapFlow(flow: Flow<T>): Emitter<T> {
        return object : Emitter<T> {
            override val flow: Flow<T> = flow
        }
    }

    /**
     * Emits an event whenever [container] changes, instead of hand-writing an
     * `eventBus += ...` call inside a thread action. [onChange] produces the event for every
     * later value; [initial] (defaults to [onChange]) produces the event for the container's
     * current value at subscription time - kept as a separate factory because a StateFlow
     * always has *some* current value, so the very first emission isn't necessarily a "change"
     * a subscriber should react to.
     *
     * Deliberately two concrete event factories rather than a shared generic wrapper: EventBus
     * dispatches by `KClass`, and a generic `Initial<T>`/`New<T>` pair would collide across
     * different `T` after erasure (`Initial<Int>::class == Initial<String>::class`). Give
     * [initial] and [onChange] their own event types when a subscriber needs to tell them apart.
     */
    fun <T : Any, E : Event> notify(
        container: Datacontainer<T>,
        onChange: (T) -> E,
        initial: (T) -> E = onChange
    ) {
        emitter {
            wrapFlow(
                container.withIndex().map { (index, value) ->
                    if (index == 0) initial(value) else onChange(value)
                }
            )
        }
    }
}

