package ru.alexey.event.threads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

interface Datacontainer<T> : StateFlow<T> {
    fun update(block: (T) -> T)
}

abstract class RealDataContainer<T>(
    stateFlow: StateFlow<T>,
    
): StateFlow<T> by stateFlow, Datacontainer<T> {
 
}

fun<T> datacontainer(initial: T): RealDataContainer<T> {
    
    val innerFlow = MutableStateFlow(initial)
    
    return object : RealDataContainer<T>(innerFlow) {
        override fun update(block: (T) -> T) {
            innerFlow.update(block)
        }
    }
}