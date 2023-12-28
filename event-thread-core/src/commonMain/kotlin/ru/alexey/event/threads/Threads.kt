package ru.alexey.event.threads

@OptIn(ExperimentalStdlibApi::class)
abstract class  EventThread<T>: AutoCloseable where T: Event {

    private val actionsMutable: MutableList<suspend (Event) -> Unit> = mutableListOf()
    private val eventTypesMutable: MutableList<EventType> = mutableListOf()


    val actions: List<suspend (Event) -> Unit>
        get() = actionsMutable

    val eventTypes: List<EventType>
        get() = eventTypesMutable

    operator fun invoke(eventType: EventType, block: suspend (Event) -> Unit) {
        eventTypesMutable.add(eventType)
        actionsMutable.add(block)
    }
}

enum class EventType {
    consume, cascade, process, modification, external
}


