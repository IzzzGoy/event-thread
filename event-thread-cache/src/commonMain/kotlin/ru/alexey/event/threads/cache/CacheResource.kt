package ru.alexey.event.threads.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import ru.alexey.event.threads.resources.ObservableResource


class CacheResource<T : @Serializable Any>(
    private val cache: Cache<T>,
    private val source: MutableStateFlow<T>,
) : ObservableResource<T>, StateFlow<T> by source {
    private val mutex = Mutex()

    override suspend fun update(block: (T) -> T) {
        mutex.withLock {
            // source.value, not cache.load() - the file was already read once at construction
            // and source has been kept in sync with every write since, so re-reading it here on
            // every single update just re-does file I/O for a value already held in memory.
            runCatching {
                block(source.value)
            }.onSuccess { new ->
                cache.write(new)
                source.emit(new)
            }
        }
    }
}

inline fun<reified T: @Serializable Any> cacheJsonResource(
    key: String,
    initial: T,
    json: Json,
): ObservableResource<T> {
    val serializer = serializer<T>()
    val cache = jsonCache(
        path = pathToJSON(key),
        json = json,
        serializer = serializer
    )
    val real = runCatching {
        cache.load()
    }.onFailure {
        cache.write(initial)
    }.getOrDefault(initial)
    val source = MutableStateFlow(real)
    return CacheResource<T>(
        cache, source
    )
}

inline fun<reified T: @Serializable Any> cacheBinaryResource(
    key: String,
    initial: T,
    cbor: Cbor,
): ObservableResource<T> {
    val serializer = serializer<T>()
    val cache = binaryCache(
        path = pathToBinary(key),
        cbor = cbor,
        serializer = serializer
    )
    val real = runCatching { cache.load() }
        .onFailure { cache.write(initial) }
        .getOrDefault(initial)
    val source = MutableStateFlow(real)
    return CacheResource<T>(
        cache, source
    )
}