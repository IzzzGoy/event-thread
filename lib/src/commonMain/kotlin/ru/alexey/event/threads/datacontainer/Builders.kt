package ru.alexey.event.threads.datacontainer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import ru.alexey.event.threads.ContainerBuilder
import ru.alexey.event.threads.ScopeEventsThreadBuilder
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.Resource
import ru.alexey.event.threads.resources.flowResource

inline fun <reified T : Any> ContainerBuilder.container(initial: T) {

    val innerFlow = MutableStateFlow(initial)

    containers.put(
        T::class,
        object : RealDataContainer<T>(innerFlow) {
            override fun update(block: (T) -> T) {
                innerFlow.update(block)
            }
        } as Datacontainer<T>
    )
}

inline fun <reified T : Any> ContainerBuilder.container(initial: T, block: DatacontainerBuilder<T>.() -> Unit) {

    val proxy: ObservableResource<T> = flowResource(initial)
    var transforms: List<Transform<out Any, T>>

    DatacontainerBuilder<T>().apply(block).also {
        /*proxy = it.proxy
            ?: initial?.let(::flowResource)
                    ?: error("Set observable resource of type <${T::class.simpleName}> or initial value")*/
        transforms = it.transforms
    }

    val trasformed: Flow<T> = transforms.fold(proxy as Flow<T>) { acc, (flow, transform) ->
        flow().combine(acc, transform)
    }

    val result = trasformed.stateIn(
        scope = CoroutineScope(Dispatchers.Default),
        started = SharingStarted.Lazily,
        initialValue = proxy.value
    )

    object : RealDataContainer<T>(result) {

        override fun update(block: (T) -> T) {
            proxy.update(block)
        }

        init {
            containers.put(T::class, this as Datacontainer<T>)
        }
    }
}

inline fun <reified T : Any> ContainerBuilder.container(block: DatacontainerBuilder<T>.() -> Unit) {

    var proxy: ObservableResource<T>
    var transforms: List<Transform<out Any, T>>

    DatacontainerBuilder<T>().apply(block).also {
        proxy = it.proxy ?: error("Set observable resource of type <${T::class.simpleName}> or initial value")
        transforms = it.transforms
    }

    val trasformed: Flow<T> = transforms.fold(proxy as Flow<T>) { acc, (flow, transform) ->
        flow().combine(acc, transform)
    }

    val result = trasformed.stateIn(
        scope = CoroutineScope(Dispatchers.Default),
        started = SharingStarted.Lazily,
        initialValue = proxy.value
    )

    object : RealDataContainer<T>(result) {

        override fun update(block: (T) -> T) {
            proxy.update(block)
        }

        init {
            containers.put(T::class, this as Datacontainer<T>)
        }
    }
}


data class Transform<Other : Any, T : Any>(
    val other: () -> Flow<Other>,
    val action: suspend (@UnsafeVariance Other, @UnsafeVariance T) -> T
)

class DatacontainerBuilder<T : Any> {

    val transforms = mutableListOf<Transform<out Any, T>>()

    var proxy: ObservableResource<T>? = null


    inline fun <reified Other : Any> ContainerBuilder.transform(noinline block: suspend (Other, T) -> T) {
        val t = Transform(
            other = {
                flow {
                    mutex.withLock {}
                    containers.get(Other::class)?.let {
                        emitAll(it as Datacontainer<Other>)
                    }
                }
            },
            action = block
        )
        transforms.add(t)
    }

    inline fun <reified R : Any> ScopeEventsThreadBuilder.resource() {
        proxy = resources.resolveObserved<R>() as? ObservableResource<T>
    }
}