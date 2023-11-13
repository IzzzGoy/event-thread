package ru.alexey.event.threads.cache

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.extensions.cached
import io.github.xxfast.kstore.utils.ExperimentalKStoreApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.internal.FusibleFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.ResourcesBuilder


class JSONDatastoreResource<T : @Serializable Any>(
    private val store: KStore<T>,
    source: StateFlow<T>,
) : ObservableResource<T>, StateFlow<T> by source {
    override suspend fun update(block: (T) -> T) {
        store.update {
            it?.let(block)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalKStoreApi::class)
inline fun <reified T : @Serializable Any> ResourcesBuilder.cacheJSONResource(
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
            initialValue = store.cached ?: default,
        )
    )
}