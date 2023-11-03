package ru.alexey.event.threads

@OptIn(ExperimentalStdlibApi::class)
abstract class  EventThread<T>: AutoCloseable where T: Event {

    private val actionsMutable: MutableList<(Event) -> Unit> = mutableListOf()

    val actions: List<(Event) -> Unit>
        get() = actionsMutable

    operator fun invoke(block: (Event) -> Unit) {
        actionsMutable.add(block)
    }
}

