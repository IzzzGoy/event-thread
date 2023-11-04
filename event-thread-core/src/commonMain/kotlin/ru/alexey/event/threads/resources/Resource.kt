package ru.alexey.event.threads.resources

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.reflect.KClass


interface Resource<T> {
    operator fun invoke(): T
}


interface ObservableResource<T> : StateFlow<T>, Resource<T> {
    suspend fun update(block: (T) -> T)

    override fun invoke(): T = value
}

class FlowResource<T>(
    private val source: MutableStateFlow<T>
): ObservableResource<T>, StateFlow<T> by source {
    override suspend fun update(block: (T) -> T) {
        source.update(block)
    }
}

class ValueResource<T> (
    private val initial: T
): Resource<T> {
    override fun invoke(): T {
        return initial
    }
}

inline fun<reified T: Any> flowResource(initial: T): ObservableResource<T> {
    val source = MutableStateFlow(initial)
    return FlowResource(source)
}

inline fun<reified T: Any>valueResource(initial: T): Resource<T> {
    return ValueResource(initial)
}

typealias Parameters = Map<KClass<out Any>, () -> Any>
