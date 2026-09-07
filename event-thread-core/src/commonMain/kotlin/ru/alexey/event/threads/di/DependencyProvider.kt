package ru.alexey.event.threads.di

import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass

interface DependencyProvider {
    fun <T : Any> get(clazz: KClass<T>, qualifier: Qualifier? = null, parameters: Parameters = emptyMap()): T
    fun <T : Any> getOrNull(clazz: KClass<T>, qualifier: Qualifier? = null, parameters: Parameters = emptyMap()): T?
}

inline fun <reified T : Any> DependencyProvider.get(
    qualifier: Qualifier? = null,
    noinline parameters: MutableMap<KClass<out Any>, () -> Any>.() -> Unit = {}
): T = get(T::class, qualifier, buildMap(parameters))

inline fun <reified T : Any> DependencyProvider.getOrNull(
    qualifier: Qualifier? = null,
    noinline parameters: MutableMap<KClass<out Any>, () -> Any>.() -> Unit = {}
): T? = getOrNull(T::class, qualifier, buildMap(parameters))
