package ru.alexey.event.threads.resources

import ru.alexey.event.threads.Builder
import ru.alexey.event.threads.ScopeBuilder
import kotlin.properties.Delegates
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


/** Declares a scope-local [Resource]`<T>` factory, deferred until [Parameters] are available -
 * `val json by resource { params -> valueResource(...) }`. Call the resulting property's value
 * with `()` (see the `invoke` operators below) to actually build the [Resource]. */
@Builder
inline fun <reified T : Any> resource(noinline block: (Parameters) -> Resource<T>): ReadOnlyProperty<Any?, (Parameters) -> Resource<T>> {
    return ReadOnlyProperty<Any?, (Parameters) -> Resource<T>> { thisRef, property ->
        block
    }
}

/** [resource], for an [ObservableResource] factory instead of a plain [Resource] one. */
@Builder
inline fun <reified T : Any> observable(noinline block: (Parameters) -> ObservableResource<T>): ReadOnlyProperty<Any?, (Parameters) -> ObservableResource<T>> {
    return ReadOnlyProperty<Any?, (Parameters) -> ObservableResource<T>> { thisRef, property ->
        block
    }
}

/** Looks up [T] in these [Parameters], throwing if it's missing. */
inline fun <reified T : Any> Parameters.resolve(): T {
    return get(T::class)?.let { it() as T }
        ?: error("Param type <${T::class.simpleName}> missing") //error("Param type <${T::class.qualifiedName}> missing")
}

/** [resolve], returning `null` instead of throwing when [T] is missing. */
inline fun <reified T : Any> Parameters.resolveOrNull(): T? {
    return get(T::class)?.let { it() as T }
}

/** [resolve], returning [default] instead of throwing when [T] is missing (or of the wrong
 * type). */
inline fun <reified T : Any> Parameters.resolveOrDefault(default: T): T {
    return get(T::class)?.let { it() as? T } ?: default
}

/** Adds an entry of type [T] to a [Parameters] map under construction - `buildMap { param { "x"
 * } }`. */
inline fun <reified T : Any> MutableMap<KClass<out Any>, () -> Any>.param(noinline block: () -> T) {
    put(T::class, block)
}


/** Builds the [Resource] this factory describes, from [parameters] assembled via the `param { }`
 * DSL. */
inline operator fun <reified T : Any> ((Parameters) -> Resource<T>).invoke(noinline parameters: MutableMap<KClass<out Any>, () -> Any>.() -> Unit = {}): Resource<T>
        = this(
    buildMap {
        apply(parameters)
    })

/** [invoke], for an [ObservableResource] factory instead of a plain [Resource] one. */
inline operator fun <reified T : Any> ((Parameters) -> ObservableResource<T>).invoke(noinline parameters: MutableMap<KClass<out Any>, () -> Any>.() -> Unit = {}): ObservableResource<T>
        = this(
    buildMap {
        apply(parameters)
    })
