package ru.alexey.event.threads.datacontainer

import ru.alexey.event.threads.resources.ObservableResource
import kotlin.reflect.KClass

/** A typed handle for resolving a [Datacontainer] via [ru.alexey.event.threads.Scope.get] instead
 * of a raw [KClass] - pairs the container's type with the [ObservableResource] it was built from
 * and, optionally, a [name] distinguishing it from another same-type container in the same scope
 * (multi-binding - see [ru.alexey.event.threads.datacontainer.ContainerBuilder]). `null` (the
 * default) means "the only container of this type in the scope" - the same behavior as before
 * multi-binding existed. Build one with [datacontainerKey]. */
class DatacontainerKey<T : Any>(
    val keyKClass: KClass<T>,
    val source: ObservableResource<T>,
    val name: String? = null,
)

/** Builds a [DatacontainerKey] for [T], inferred from the reified type parameter. See
 * [DatacontainerKey.name] for what [name] does. */
inline fun <reified T : Any> datacontainerKey(
    resource: ObservableResource<T>,
    name: String? = null,
): DatacontainerKey<T> {
    return DatacontainerKey(T::class, resource, name)
}
