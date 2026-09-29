package ru.alexey.event.threads.datacontainer

import ru.alexey.event.threads.resources.ObservableResource
import kotlin.reflect.KClass

class DatacontainerKey<T : Any>(
    val keyKClass: KClass<T>,
    val source: ObservableResource<T>
)

inline fun <reified T : Any> datacontainerKey(resource: ObservableResource<T>): DatacontainerKey<T> {
    return DatacontainerKey(T::class, resource)
}