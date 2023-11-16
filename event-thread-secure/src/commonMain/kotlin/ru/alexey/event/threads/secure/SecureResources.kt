package ru.alexey.event.threads.secure

import com.liftric.kvault.KVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.*
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.ResourcesFactory
import kotlin.reflect.KClass
import kotlinx.serialization.cbor.Cbor

class SecureResource<T: @Serializable Any> @OptIn(ExperimentalSerializationApi::class) constructor(
    private val clazz: KClass<T>,
    private val store: KVault,
    private val cbor: Cbor,
    private val source: MutableStateFlow<T>,
    private val key: String
) : StateFlow<T> by source, ObservableResource<T> {
    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    override suspend fun update(block: (T) -> T) {
        store.data(key)?.also {
            val prev = cbor.decodeFromByteArray(clazz.serializer(), it)
            val new = block(prev)
            if (store.set(key, cbor.encodeToByteArray(clazz.serializer(), new))) {
                source.emit(new)
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
inline fun<reified T: @Serializable Any> ResourcesFactory.secureResource(
    key: String,
    initial: T,
    cbor: Cbor = get()
): ObservableResource<T> {

    val store = secureStore()

    val source = MutableStateFlow(
        store.data(key)?.let {
            cbor.decodeFromByteArray(T::class.serializer(), it)
        } ?: initial.also {
            store.set(key, cbor.encodeToByteArray(it))
        }
    )

    return SecureResource(
        clazz = T::class,
        store = store,
        cbor = cbor,
        source = source,
        key = key,
    )
}