package ru.alexey.event.threads.cache

import io.github.xxfast.kstore.KStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.ResourcesBuilder


class JSONDatastoreResource<T: @Serializable Any>(
    private val store: KStore<T>,
    source: StateFlow<T>,
): ObservableResource<T>, StateFlow<T> by source {
    override suspend fun update(block: (T) -> T) {
        store.update {
            it?.let(block)
        }
    }
}

inline fun<reified T: @Serializable Any> ResourcesBuilder.cacheJSONResource(
    key: String,
    scope: CoroutineScope = get(),
    default: T = get()
): ObservableResource<T> {

    val store = storeJSONOf(key, default)


    return JSONDatastoreResource(
        store,
        store.updates.filterNotNull().stateIn(
            scope = scope,
            started = SharingStarted.Lazily,
            initialValue = default,
        )
    )
}