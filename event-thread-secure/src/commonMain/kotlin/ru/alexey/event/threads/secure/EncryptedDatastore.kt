package ru.alexey.event.threads.secure

import kotlinx.coroutines.flow.StateFlow
import ru.alexey.event.threads.resources.ObservableResource

class EncryptedResource<T>(
    source: StateFlow<T>
) : ObservableResource<T>, StateFlow<T> by source {
    override suspend fun update(block: (T) -> T) {
        
    }

}