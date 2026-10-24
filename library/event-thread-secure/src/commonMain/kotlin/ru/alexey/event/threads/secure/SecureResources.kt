package ru.alexey.event.threads.secure

import com.liftric.kvault.KVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.*
import ru.alexey.event.threads.resources.ObservableResource
import kotlin.reflect.KClass
import kotlinx.serialization.cbor.Cbor

class SecureResource<T: @Serializable Any> @OptIn(ExperimentalSerializationApi::class) constructor(
    private val clazz: KClass<T>,
    private val store: KVault,
    private val cbor: Cbor,
    private val source: MutableStateFlow<T>,
    private val key: String
) : StateFlow<T> by source, ObservableResource<T> {
    // Same shape as `CacheResource.update()` (event-thread-cache), for the same two reasons:
    // - source.value, not a re-read via store.data(key) - the KVault entry was already read once
    //   at construction and source has been kept in sync with every successful write since, so
    //   re-reading (an Android Keystore-backed decrypt) on every single update is both wasted work
    //   and, if the entry were ever missing, silently a no-op instead of a visible failure.
    // - block/store.set are allowed to throw/fail loudly instead of being silently swallowed - the
    //   previous version's `store.data(key)?.also { ... if (store.set(...)) { emit } }` did
    //   nothing at all (no write, no emit, no error) if the key was missing or the write failed,
    //   unlike every other error path in this library.
    // - a Mutex, which the previous version didn't have at all: without it, two concurrent
    //   update() calls can both read the same `source.value`, then both write - one silently
    //   overwriting the other's result (the same race event-thread-cache's CacheResource already
    //   guards against for its own persisted container).
    private val mutex = Mutex()

    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    override suspend fun update(block: (T) -> T) {
        mutex.withLock {
            val new = block(source.value)
            val written = store.set(key, cbor.encodeToByteArray(clazz.serializer(), new))
            check(written) { "SecureResource: KVault failed to persist the updated value for key '$key'" }
            source.emit(new)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
inline fun<reified T: @Serializable Any> secureResource(
    key: String,
    initial: T,
    cbor: Cbor
): ObservableResource<T> {

    val store = secureStore()

    // Same fallback idiom as event-thread-cache's cacheJsonResource/cacheBinaryResource: a
    // missing OR corrupt/incompatible stored entry (e.g. T's shape changed between app versions)
    // heals to `initial` and rewrites it, instead of `decodeFromByteArray` throwing straight out
    // of scope construction - the previous version only handled "missing", not "corrupt".
    val real = runCatching {
        store.data(key)?.let { cbor.decodeFromByteArray(T::class.serializer(), it) }
            ?: throw NoSuchElementException("no value stored under key '$key'")
    }.onFailure {
        store.set(key, cbor.encodeToByteArray(initial))
    }.getOrDefault(initial)

    val source = MutableStateFlow(real)

    return SecureResource(
        clazz = T::class,
        store = store,
        cbor = cbor,
        source = source,
        key = key,
    )
}