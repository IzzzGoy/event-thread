package ru.alexey.event.threads.emitter

import kotlinx.coroutines.flow.Flow
import ru.alexey.event.threads.Event
import ru.alexey.event.threads.Scope

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
}

