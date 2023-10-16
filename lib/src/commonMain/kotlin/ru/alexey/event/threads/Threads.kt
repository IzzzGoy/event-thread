package ru.alexey.event.threads

@OptIn(ExperimentalStdlibApi::class)
interface EventThread<T>: AutoCloseable where T: Event, T: Any {
    
    val action: (Event) -> Unit
    override fun close() {
        TODO("Not yet implemented")
    }


}

interface ScopeEventsThreadBuilder {
    val eventBus: EventBus
}

fun eventsBuilder(eventBus: EventBus, block: ScopeEventsThreadBuilder.() -> Unit) {
    object : ScopeEventsThreadBuilder {
        override val eventBus: EventBus = eventBus
        init {
            block()
        }
    }
}

inline fun<reified T: Event> ScopeEventsThreadBuilder.eventThread(noinline action: (T) -> Unit) {
    object : EventThread<T> {
        override val action: (Event) -> Unit = {
            if (it is T) { action(it) }
        }


        override fun close() {
            eventBus.unsubscribe<T>()
        }
    }.also {
        eventBus { it }
    }
}

