package ru.alexey.event.threads.emitter

import kotlinx.coroutines.flow.Flow
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.resources.Resource

/** An external source of [Event]s fed into a [ru.alexey.event.threads.Scope]'s bus - register one
 * via `emitters { emitter { } }` (see [ru.alexey.event.threads.emitter.EmittersBuilder]). Build
 * one from a plain [Flow] with [EmittersBuilder.wrapFlow]. */
interface Emitter<T: Event> {
    val flow: Flow<T>
}

/** An [Emitter] that is also a [Resource] - for a source that needs the same load/refresh
 * lifecycle as other resources, not just a bare [Flow]. */
interface EmitterResource<T: Event> : Emitter<T>, Resource<T>

/*
inline fun<reified T: Event> ResourceProvider.emitterResource()
    = this.resource(T::class) as? EmitterResource<T> ?: error("Emitter resource with type <${T::class.simpleName}> not found")*/
