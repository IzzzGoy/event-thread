package ru.alexey.event.threads

import androidx.compose.runtime.compositionLocalOf
import kotlin.reflect.KClass

/**
 * Per-composition storage that [scope] mixes into every scope's [ru.alexey.event.threads.
 * resources.Parameters] as [savedParams] - lets state survive a scope being freed and reloaded
 * (e.g. navigating away and back) without the caller re-passing it explicitly every time. The
 * built-in implementation ([DefaultStateSaver]) is in-memory only, scoped to the current
 * [LocalStateSaver] provider; nothing here persists across process death.
 */
interface StateSaver {

    /** Every currently saved value, as a [ru.alexey.event.threads.resources.Parameters]-shaped
     * map merged into a scope's load parameters by [scope]. */
    val savedParams: Map<KClass<out Any>, () -> Any>

    /** Saves [instance] under [clazz], overwriting any previous value for that type. */
    fun <T : Any> save(clazz: KClass<T>, instance: T)

    /** Reads back the last value [save]d for [clazz], or `null` if none. */
    fun <T : Any> load(clazz: KClass<T>): T?

    /** Forgets the saved value for [clazz], if any. */
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

/** The [StateSaver] for the current composition - provided by [ru.alexey.event.threads.
 * ScopeHolder] (the composable), read by [scope]. */
val LocalStateSaver = compositionLocalOf<StateSaver> {
    error("Provide State Saver")
}