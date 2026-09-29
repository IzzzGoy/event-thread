package ru.alexey.event.threads.resources

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


/** A single readable value, usable directly (`resource()`) or as a property delegate. Sources for
 * [ru.alexey.event.threads.datacontainer.Datacontainer]s and scope-local config values. See
 * [ValueResource] (fixed) and [ObservableResource] (reactive) for the two shapes this comes in. */
interface Resource<T>: ReadOnlyProperty<Any?, T> {
    operator fun invoke(): T

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return invoke()
    }
}


/** A [Resource] that's also a [StateFlow] - the shape [ru.alexey.event.threads.datacontainer.
 * Datacontainer] sources are built from. See [FlowResource] (plain, in-memory) and
 * [refreshingResource] (re-fetched on a trigger). */
interface ObservableResource<T> : StateFlow<T>, Resource<T> {
    /** Replaces the current value with `block(currentValue)`. */
    suspend fun update(block: (T) -> T)

    override fun invoke(): T = value
}

/** The plain, in-memory [ObservableResource] - backed by a [MutableStateFlow], no persistence,
 * no external fetch. Build one with [flowResource]. */
class FlowResource<T>(
    private val source: MutableStateFlow<T>
): ObservableResource<T>, StateFlow<T> by source {
    override suspend fun update(block: (T) -> T) {
        source.update(block)
    }

    /** Replaces the current value directly, without going through [update]'s fold. */
    fun set(value: T) {
        source.value = value
    }
}

/** A [Resource] whose value is fixed at construction and never changes. Build one with
 * [valueResource]. */
class ValueResource<T> (
    private val initial: T
): Resource<T> {
    override fun invoke(): T {
        return initial
    }
}

/** Builds a [FlowResource] seeded with [initial]. */
inline fun<reified T: Any> flowResource(initial: T): FlowResource<T> {
    val source = MutableStateFlow(initial)
    return FlowResource(source)
}

/** Builds a [ValueResource] fixed at [initial]. */
inline fun<reified T: Any>valueResource(initial: T): Resource<T> {
    return ValueResource(initial)
}

/** The parameters a scope is loaded/built with, or a resource/screen is resolved with - a plain
 * type-keyed lookup table built via the `MutableMap<KClass<out Any>, () -> Any>.param<T> { }` DSL
 * (see [param]) and read back via [resolve]/[resolveOrNull]/[resolveOrDefault]. */
typealias Parameters = Map<KClass<out Any>, () -> Any>
