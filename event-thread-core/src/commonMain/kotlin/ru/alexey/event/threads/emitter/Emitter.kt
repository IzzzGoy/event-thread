package ru.alexey.event.threads.emitter

import kotlinx.coroutines.flow.Flow
import ru.alexey.event.threads.Event
import ru.alexey.event.threads.resources.Resource
import ru.alexey.event.threads.resources.ResourceProvider

interface Emitter<T: Event> {
    val flow: Flow<T>
}

interface EmitterResource<T: Event> : Emitter<T>, Resource<T>

inline fun<reified T: Event> ResourceProvider.emitterResource()
    = this.resource(T::class) as? EmitterResource<T> ?: error("Emitter resource with type <${T::class.simpleName}> not found")