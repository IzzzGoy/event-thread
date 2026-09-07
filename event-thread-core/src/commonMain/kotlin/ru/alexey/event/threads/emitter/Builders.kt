package ru.alexey.event.threads.emitter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.datacontainer.Datacontainer

class EmittersBuilder {
    private val emitterFactories = mutableListOf<Scope.() -> Emitter<out Event>>()
    fun build(scope: Scope) = emitterFactories.map {
        with(scope) {
            it().also { eventBus.collectToEventBus(it.flow) }
        }
    }

    fun <T : Event> emitter(block: Scope.() -> Emitter<T>) {
        emitterFactories.add(block)
    }

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

