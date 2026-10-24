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
            //
            // block/cache.write are deliberately allowed to throw straight out of update() (no
            // runCatching here) - an earlier version swallowed a throwing block silently (no
            // write, no emit, no error reported anywhere), unlike every other container/action
            // failure path in this library, which reports via EventBus's onError. Letting it
            // propagate means a `.then(cacheContainer) { ... }` cascade gets the same "aborts this
            // action chain, reports via onError" handling as any other thread action - see
            // EventBus.runActions. source is only updated once cache.write has actually
            // succeeded, so memory and disk never disagree about whether an update "happened".
            val new = block(source.value)
            cache.write(new)
            source.emit(new)
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