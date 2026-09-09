package ru.alexey.event.threads.datacontainer

import ru.alexey.event.threads.resources.ObservableResource
import kotlin.reflect.KClass

/** A typed handle for resolving a [Datacontainer] via [ru.alexey.event.threads.Scope.get] instead
 * of a raw [KClass], pairing the container's type with the [ObservableResource] it was built
 * from. Build one with [datacontainerKey]. */
class DatacontainerKey<T : Any>(
    val keyKClass: KClass<T>,
    val source: ObservableResource<T>
)

/** Builds a [DatacontainerKey] for [T], inferred from the reified type parameter. */
inline fun <reified T : Any> datacontainerKey(resource: ObservableResource<T>): DatacontainerKey<T> {
    return DatacontainerKey(T::class, resource)
}