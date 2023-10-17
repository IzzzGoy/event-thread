package ru.alexey.event.threads

@OptIn(ExperimentalStdlibApi::class)
interface EventThread<T>: AutoCloseable where T: Event, T: Any {
    
    var action: (Event) -> Unit
    override fun close() {
        TODO("Not yet implemented")
    }


}

interface Buildable {
    fun build()
}

interface ScopeEventsThreadBuilder {
    val eventBus: EventBus
    val buildable: MutableSet<Buildable>
}

interface ThreadActionBuilder<TYPE, DC: Datacontainer<TYPE>> : ScopeEventsThreadBuilder {
    val dcContext: () -> DC
}

fun eventsBuilder(eventBus: EventBus, block: ScopeEventsThreadBuilder.() -> Unit) {
    object : ScopeEventsThreadBuilder {
        override val eventBus: EventBus = eventBus
        override val buildable: MutableSet<Buildable> = mutableSetOf()
        init {
            block()
            buildable.forEach(Buildable::build)
        }
    }
}



inline fun<reified T: Event> ScopeEventsThreadBuilder.eventThread(noinline action: (T) -> Unit) : EventThread<T> {

    return object : EventThread<T>, Buildable {
        override var action: (Event) -> Unit = {
            if (it is T) {
                action(it)
            }
        }
        override fun close() {
            eventBus.unsubscribe<T>()
        }

        override fun build() {
            eventBus { this }
        }
    }.also {
        this.buildable.add(it)
    }
}


inline fun<reified T: Event, TYPE, DC: Datacontainer<TYPE>> EventThread<T> .bind(dc: DC, noinline action: TYPE.(T) -> TYPE) {

     this.action = {
        if (it is T) {
            dc.update { type ->
                type.action(it)
            }
        }
    }
}



