package ru.alexey.event.threads

@OptIn(ExperimentalStdlibApi::class)
abstract class  EventThread<T>: AutoCloseable where T: Event {

    private val actionsMutable: MutableList<suspend (Event) -> Unit> = mutableListOf()

    val actions: List<suspend (Event) -> Unit>
        get() = actionsMutable

    operator fun invoke(block: suspend (Event) -> Unit) {
        actionsMutable.add(block)
    }
}


