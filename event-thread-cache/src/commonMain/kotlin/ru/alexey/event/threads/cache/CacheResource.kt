package ru.alexey.event.threads.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.ResourcesFactory


class CacheResource<T : @Serializable Any>(
    private val cache: Cache<T>,
    private val source: MutableStateFlow<T>,
) : ObservableResource<T>, StateFlow<T> by source {
    override suspend fun update(block: (T) -> T) {
        val new = block(cache.load())
        cache.write(new)
        source.emit(new)
    }
}

@OptIn(InternalSerializationApi::class)
inline fun<reified T: @Serializable Any> ResourcesFactory.cacheJsonRecourse(
    key: String,
    initial: T,
    json: Json = get(),
): ObservableResource<T> {
    val serializer = T::class.serializer()
    val cache = jsonCache(
        path = pathToJSON(key),
        json = json,
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

@OptIn(InternalSerializationApi::class)
inline fun<reified T: @Serializable Any> ResourcesFactory.cacheBinaryRecourse(
    key: String,
    initial: T,
    cbor: Cbor = get(),
): ObservableResource<T> {
    val serializer = T::class.serializer()
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