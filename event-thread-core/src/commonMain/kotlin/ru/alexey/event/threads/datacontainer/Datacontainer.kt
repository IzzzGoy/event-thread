package ru.alexey.event.threads.datacontainer

import kotlinx.coroutines.flow.StateFlow


interface Datacontainer<T> : StateFlow<T> {
    suspend fun update(block: (T) -> T)
}

abstract class RealDataContainer<T>(
    stateFlow: StateFlow<T>
) : StateFlow<T> by stateFlow, Datacontainer<T>


