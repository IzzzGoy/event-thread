package ru.alexey.event.threads.di

import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass

/**
 * The seam between this library and whatever DI mechanism (or none) the app already uses -
 * passed to [ScopeBuilder][ru.alexey.event.threads.ScopeBuilder]/`scopeBuilder(...)` and exposed
 * on every [ru.alexey.event.threads.Scope] as `dependencyProvider`. [DummyProvider] is the
 * built-in, dependency-free implementation; a real framework (e.g. `event-thread-koin`) adapts
 * its own container to this interface instead of this library depending on it directly.
 * Implementations are expected to propagate their own lookup-failure exceptions uncaught from
 * [get] (fail-fast, matching [DummyProvider]'s `error(...)`); use [getOrNull] where a missing
 * registration is an expected, handleable case rather than a configuration bug.
 */
interface DependencyProvider {
    /** Resolves [T], throwing if nothing is registered for [clazz]/[qualifier]. */
    fun <T : Any> get(clazz: KClass<T>, qualifier: Qualifier? = null, parameters: Parameters = emptyMap()): T
    /** [get], returning `null` instead of throwing when nothing is registered. */
    fun <T : Any> getOrNull(clazz: KClass<T>, qualifier: Qualifier? = null, parameters: Parameters = emptyMap()): T?
}

/** [DependencyProvider.get] with [T] inferred from the reified type parameter, and [parameters]
 * built via the same [MutableMap] DSL used elsewhere for [Parameters]. */
inline fun <reified T : Any> DependencyProvider.get(
    qualifier: Qualifier? = null,
    noinline parameters: MutableMap<KClass<out Any>, () -> Any>.() -> Unit = {}
): T = get(T::class, qualifier, buildMap(parameters))

/** [DependencyProvider.getOrNull] with [T] inferred from the reified type parameter. */
inline fun <reified T : Any> DependencyProvider.getOrNull(
    qualifier: Qualifier? = null,
    noinline parameters: MutableMap<KClass<out Any>, () -> Any>.() -> Unit = {}
): T? = getOrNull(T::class, qualifier, buildMap(parameters))
