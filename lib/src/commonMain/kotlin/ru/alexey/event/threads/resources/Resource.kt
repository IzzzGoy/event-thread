package ru.alexey.event.threads.resources

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

interface Resource<T> {
    operator fun invoke(): T
}


interface ObservableResource<T> : StateFlow<T>, Resource<T> {
    fun update(block: (T) -> T)

    override fun invoke(): T = value
}

class FlowResource<T>(
    private val source: MutableStateFlow<T>
): ObservableResource<T>, StateFlow<T> by source {
    override fun update(block: (T) -> T) {
        source.update(block)
    }
}

inline fun<reified T: Any> flowResource(initial: T): ObservableResource<T> {
    val source = MutableStateFlow(initial)
    return FlowResource(source)
}