package ru.alexey.event.threads.datacontainer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.alexey.event.threads.Builder
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.foldAndStateWithProxyAndWatchers
import ru.alexey.event.threads.resources.ObservableResource
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass


/** Per-[ru.alexey.event.threads.Scope] registry of [Datacontainer]s, keyed by their value type.
 * Owned by [ScopeBuilder] (see [ScopeBuilder.containerBuilder]) - registered via the
 * `datacontainer { }` delegate below, not mutated directly in normal usage. */
class ContainerBuilder {
    private val containers: MutableMap<KClass<out Any>, Datacontainer<out Any>>
            = mutableMapOf()

    val containersEntries: Map<KClass<out Any>, Datacontainer<out Any>>
        get() = containers

    operator fun<T: Any> set(kClass: KClass<T>, container: Datacontainer<T>) {
        containers[kClass] = container
    }
    operator fun<T: Any> get(kClass: KClass<T>): Datacontainer<T>?
        = containers[kClass] as? Datacontainer<T>

    /** Copies [entries] into this registry - the mechanism behind `implements` inheriting a
     * parent scope's containers (see [ScopeBuilder.apply]). */
    fun apply(entries: Map<KClass<out Any>, Datacontainer<out Any>>) {
        containers.putAll(entries)
    }
}


/**
 * Declares a [Datacontainer]`<T>` on this scope: `val todos by datacontainer(flowResource(...))
 * { transform(other) { ... } }`. [source] seeds the container's initial value and is what
 * [Datacontainer.update] ultimately writes through to; [block] configures derived [transform]
 * chains and [watcher]s via [DatacontainerBuilder]. Idempotent per scope - resolving the same
 * `T::class` again (e.g. from an `implements`-inherited copy) returns the already-built
 * container instead of building a second, independent one.
 */
inline fun<reified T: Any> ScopeBuilder.datacontainer(
    source: ObservableResource<T>,
    crossinline block: DatacontainerBuilder<T>.() -> Unit
) = ReadOnlyProperty<ScopeBuilder?, Datacontainer<T>> { _, _ ->
    val container = containerBuilder[T::class]
    if (container == null) {
        var transforms: List<Transform<out Any, T>>
        var scope: CoroutineScope
        var watchers: List<(T) -> Unit>

        DatacontainerBuilder<T>().apply(block).build().also {
            transforms = it.transforms
            scope = it.coroutineScope
            watchers = it.watchers
        }
        // A supervised child of the configured scope, not that scope itself: `realDataContainer`
        // cancels whatever scope it's given when the container closes. Without this, a container
        // declared with `.coroutineScope { viewModelScope }` (or any other scope the caller still
        // needs elsewhere) would take that scope down entirely - and everything else backed by
        // it - the moment this one container's owning Scope closes.
        val containerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val mutex = Mutex()
        with(containerBuilder) {
            realDataContainer(transforms.foldAndStateWithProxyAndWatchers(source, watchers, containerScope), containerScope) { block: suspend (T) -> T ->
                mutex.withLock {
                    val new = block(source.value)
                    source.update { new }
                }
            }
        }
    }  else {
        container
    }
}

/** Resolves a [Datacontainer]`<T>` this scope expects to already have, typically one it inherited
 * from an `implements`d parent - throws if [T] was never registered, since that means the
 * expected inheritance didn't happen rather than "not present yet". */
inline fun<reified T: Any> ScopeBuilder.parent() = ReadOnlyProperty<ScopeBuilder?, Datacontainer<T>>  { thisRef, property ->
    containerBuilder[T::class] ?: throw Exception("Container with type ${T::class.simpleName} can`t be inherited")
}

/** One `transform(otherContainer) { other, current -> }` step: recomputes this container's value
 * as [other] emits, folding [action] over the container's current value. See
 * [DatacontainerBuilder.transform]. */
data class Transform<Other : Any, T : Any>(
    val other: () -> Flow<Other>,
    val action: suspend ( Other, @UnsafeVariance T) -> T
)


/** The built configuration produced by [DatacontainerBuilder.build]. */
interface DataContainerConfig<T: Any> {
    val transforms: List<Transform<out Any, T>>
    val proxy: ObservableResource<T>?
    val coroutineScope: CoroutineScope
    val watchers: List<(T) -> Unit>
}

/** Receiver for `datacontainer(source) { }` - configures derived [transform] chains, [watcher]s,
 * and which [CoroutineScope] the container's fold/update machinery runs on. */
class DatacontainerBuilder<T : Any> {

    private val transforms = mutableListOf<Transform<out Any, T>>()

    private var proxy: ObservableResource<T>? = null

    private var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)

    private val watchers = mutableListOf<(T) -> Unit>()

    fun build(): DataContainerConfig<T> {
        val t = transforms
        val p = proxy
        val c = coroutineScope
        val w = watchers
        return object : DataContainerConfig<T> {
            override val transforms: List<Transform<out Any, T>> = t
            override val proxy: ObservableResource<T>? = p
            override val coroutineScope: CoroutineScope = c
            override val watchers: List<(T) -> Unit> = w
        }
    }

    /** Registers a plain callback invoked with the container's new value on every change - for
     * side effects (logging, etc.), not for deriving other state (use [transform] for that). */
    fun watcher(watcher: (T) -> Unit) {
        watchers.add(watcher)
    }

    @Deprecated("Replaced by new dc definition syntax", ReplaceWith("transform(otherDC, block)"))
    fun <Other : Any>ContainerBuilder.transform(clazz: KClass<Other>, block: suspend (Other, T) -> T) {
        val cb = this
        val t = Transform(
            other = {
                flow {
                    cb[clazz]?.let {
                        emitAll(it)
                    }
                }
            },
            action = block
        )
        transforms.add(t)
    }

    @Deprecated("Replaced by new dc definition syntax", ReplaceWith("transform(otherDC, block)"))
    @Builder
    inline fun <reified Other : Any> ContainerBuilder.transform(noinline block: suspend (Other, T) -> T) {
        transform(Other::class, block)
    }

    /** Recomputes this container's value whenever [otherContainer] emits, by applying [block] to
     * its new value and this container's current value. Multiple `transform` calls chain: each
     * runs in registration order against the previous step's result (see `TodoListBase` in the
     * sample app for a worked example combining two source containers). */
    @Builder
    fun <Other : Any> transform(otherContainer: Datacontainer<Other>, block: suspend (Other, T) -> T) {
        val t = Transform(
            other = {
                otherContainer
            },
            action = block
        )
        transforms.add(t)
    }

    /** Sets the [CoroutineScope] this container's fold/update machinery runs on. Defaults to a
     * fresh `CoroutineScope(Dispatchers.Default)` if never called. */
    fun coroutineScope(block: () -> CoroutineScope) {
        coroutineScope = block()
    }
}
