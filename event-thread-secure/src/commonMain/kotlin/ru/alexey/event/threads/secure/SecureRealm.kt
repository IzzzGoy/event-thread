package ru.alexey.event.threads.secure

import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.types.RealmObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.ResourcesFactory
import kotlin.jvm.JvmInline
import kotlin.random.Random
import kotlin.reflect.KClass

@JvmInline
value class Seed(val seed: Long)

fun createConfig(vararg objects: KClass<out RealmObject>, seed: Seed, config: RealmConfiguration.Builder.() -> Unit) =
    RealmConfiguration.Builder(
        objects.toSet()
    ).apply(config)
        .encryptionKey(
            Random(seed.seed).nextBytes(64)
        )
        .build()


interface WrappedList<T> {
    val list: List<T>
}

fun <T> List<T>.wrap() = object : WrappedList<T> {
    override val list: List<T> = this@wrap
}

class SecureRealm<R : RealmObject, T : WrappedList<R>>(
    private val realm: Realm,
    private val clazz: KClass<R>,
    private val source: StateFlow<T>,
) : StateFlow<T> by source, ObservableResource<T> {
    override suspend fun update(block: (T) -> T) {
        realm.write {
            val old = query(clazz = clazz).find()
            val new = old.wrap().apply { block(this as T) }.list
            old.filter { it !in new }.forEach { delete(it) }
            new.filter { it !in old }.forEach { copyToRealm(it) }
        }
    }
}

inline fun <reified R : RealmObject, reified T : WrappedList<R>> ResourcesFactory.secureDatabase(
    realm: Realm = get(),
    scope: CoroutineScope = get()
): ObservableResource<T> {
    val source: StateFlow<T> = realm.query(clazz = R::class).find().asFlow().map {
        it.list.wrap() as T
    }.stateIn(
        scope = scope,
        initialValue = emptyList<R>().wrap() as T,
        started = SharingStarted.Lazily
    )
    return SecureRealm<R, T>(
        realm,
        clazz = R::class,
        source = source
    )
}
