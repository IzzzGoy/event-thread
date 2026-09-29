package ru.alexey.event.threads

import androidx.compose.runtime.compositionLocalOf
import kotlin.reflect.KClass

interface StateSaver {

    val savedParams: Map<KClass<out Any>, () -> Any>
    fun <T : Any> save(clazz: KClass<T>, instance: T)

    fun <T : Any> load(clazz: KClass<T>): T?

    fun <T : Any> remove(clazz: KClass<T>)
}

internal class DefaultStateSaver : StateSaver {
    private val map = mutableMapOf<KClass<*>, Any>()
    override val savedParams: Map<KClass<out Any>, () -> Any>
        get() = map.mapValues {
            { it.value }
        }

    override fun <T : Any> save(clazz: KClass<T>, instance: T) {
        map[clazz] = instance
    }

    override fun <T : Any> load(clazz: KClass<T>): T? {
        return map[clazz] as? T
    }

    override fun <T : Any> remove(clazz: KClass<T>) {
        map.remove(clazz)
    }
}

val LocalStateSaver = compositionLocalOf<StateSaver> {
    error("Provide State Saver")
}