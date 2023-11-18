package ru.alexey.event.threads.emitter

import ru.alexey.event.threads.Event
import ru.alexey.event.threads.Scope

class EmittersBuilder {
    private val emitterFactories = mutableListOf<Scope.() -> Emitter<out Event>>()
    fun build(scope: Scope) = emitterFactories.map {
        with(scope) {
            it().also { eventBus.collectToEventBus(it.flow) }
        }
    }

    fun<T: Event> emitter(block: Scope.() -> Emitter<T>) {
        emitterFactories.add(block)
    }
}

