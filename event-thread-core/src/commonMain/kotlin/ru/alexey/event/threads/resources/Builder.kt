package ru.alexey.event.threads.resources

import ru.alexey.event.threads.Builder
import ru.alexey.event.threads.ScopeBuilder
import kotlin.properties.Delegates
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


@Builder
inline fun <reified T : Any> resource(noinline block: (Parameters) -> Resource<T>): ReadOnlyProperty<Any?, (Parameters) -> Resource<T>> {
    return ReadOnlyProperty<Any?, (Parameters) -> Resource<T>> { thisRef, property ->
        block
    }
}

@Builder
inline fun <reified T : Any> observable(noinline block: (Parameters) -> ObservableResource<T>): ReadOnlyProperty<Any?, (Parameters) -> ObservableResource<T>> {
    return ReadOnlyProperty<Any?, (Parameters) -> ObservableResource<T>> { thisRef, property ->
        block
    }
}

inline fun <reified T : Any> Parameters.resolve(): T {
    return get(T::class)?.let { it() as T }
        ?: error("Param type <> missing") //error("Param type <${T::class.qualifiedName}> missing")
}

inline fun <reified T : Any> MutableMap<KClass<out Any>, () -> Any>.param(noinline block: () -> T) {
    put(T::class, block)
}


inline operator fun <reified T : Any> ((Parameters) -> Resource<T>).invoke(noinline parameters: MutableMap<KClass<out Any>, () -> Any>.() -> Unit = {}): Resource<T>
        = this(
    buildMap {
        apply(parameters)
    })

inline operator fun <reified T : Any> ((Parameters) -> ObservableResource<T>).invoke(noinline parameters: MutableMap<KClass<out Any>, () -> Any>.() -> Unit = {}): ObservableResource<T>
        = this(
    buildMap {
        apply(parameters)
    })
