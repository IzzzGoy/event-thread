package ru.alexey.event.threads

import ru.alexey.event.threads.utils.Builder

@OptIn(ExperimentalStdlibApi::class)
abstract class  EventThread<T: Event>: AutoCloseable {

    private val eventThreadActions: MutableList<EventThreadAction<T>> = mutableListOf()


    val actions: List<suspend (Event) -> Unit>
        get() = eventThreadActions.map { it.action as suspend (Event) -> Unit }

    val eventMetadatas: List<EventMetadata>
        get() = eventThreadActions.map { EventMetadata(it.description, it.type) }

    operator fun invoke(type: EventType, block: EventThreadActionBuilder<T>.() -> Unit) {
        eventThreadActions.add(
            EventThreadActionBuilder<T>(type).apply(block).build()
        )
    }
}

enum class EventType {
    consume, cascade, process, modification, external
}

class EventThreadAction<T: Event>(
    val type: EventType,
    val action: suspend (T) -> Unit,
    val description: String
)

data class EventMetadata(
    val description: String,
    val type: EventType
)

class EventThreadActionBuilder<T: Event>(
    private val type: EventType,
) : Builder<EventThreadAction<T>> {
    private var description: String = ""
    private var action: suspend (T) -> Unit = {}

    override fun build(): EventThreadAction<T> {
        return EventThreadAction(
            type, action, description
        )
    }

    fun description(block: () -> String) {
        description = block()
    }

    fun action(block: suspend (T) -> Unit) {
        action = block
    }
}




