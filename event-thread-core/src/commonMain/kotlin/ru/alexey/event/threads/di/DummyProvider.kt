package ru.alexey.event.threads.di

import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass

/**
 * The dependency-free default [DependencyProvider] - a plain, registerable
 * lookup table, the same shape [Parameters] already uses for manual wiring
 * elsewhere in this library, just keyed additionally by an optional
 * [Qualifier]. A real DI framework adapter (e.g. `event-thread-koin`) is an
 * option on top of this, not a requirement.
 */
class DummyProvider : DependencyProvider {
    private val registry = mutableMapOf<Pair<KClass<*>, Qualifier?>, (Parameters) -> Any>()

    fun <T : Any> register(clazz: KClass<T>, qualifier: Qualifier? = null, factory: (Parameters) -> T) {
        registry[clazz to qualifier] = factory
    }

    override fun <T : Any> get(clazz: KClass<T>, qualifier: Qualifier?, parameters: Parameters): T =
        getOrNull(clazz, qualifier, parameters)
            ?: error("No dependency registered for ${clazz.simpleName}${qualifier?.let { " (qualifier=${it.value})" } ?: ""}")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getOrNull(clazz: KClass<T>, qualifier: Qualifier?, parameters: Parameters): T? =
        registry[clazz to qualifier]?.invoke(parameters) as? T
}

inline fun <reified T : Any> DummyProvider.register(
    qualifier: Qualifier? = null,
    noinline factory: (Parameters) -> T
) = register(T::class, qualifier, factory)

fun dummyProvider(block: DummyProvider.() -> Unit): DummyProvider = DummyProvider().apply(block)
