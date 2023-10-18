package ru.alexey.event.threads.datacontainer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import ru.alexey.event.threads.ContainerBuilder

interface Datacontainer<T> : StateFlow<T> {
    fun update(block: (T) -> T)
}

abstract class RealDataContainer<T>(
    stateFlow: StateFlow<T>,
    
): StateFlow<T> by stateFlow, Datacontainer<T>

