package ru.alexey.event.threads.datacontainer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import ru.alexey.event.threads.ContainerBuilder
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.flowResource

inline fun <reified T : Any> ContainerBuilder.container(initial: T) {

    val innerFlow = MutableStateFlow(initial)

    containers[T::class] = object : RealDataContainer<T>(innerFlow) {
        override suspend fun update(block: (T) -> T) {
            innerFlow.update(block)
        }
    } as Datacontainer<T>
}

inline fun <reified T : Any> ContainerBuilder.container(initial: T, block: DatacontainerBuilder<T>.() -> Unit) {

    val proxy: ObservableResource<T> = flowResource(initial)
    var transforms: List<Transform<out Any, T>>
    var scope: CoroutineScope

    DatacontainerBuilder<T>().apply(block).also {
        transforms = it.transforms
        scope = it.coroutineScope
    }

    val trasformed: Flow<T> = transforms.fold(proxy as Flow<T>) { acc, (flow, transform) ->
        flow().combine(acc, transform)
    }

    val result = trasformed.stateIn(
        scope = scope,
        started = SharingStarted.Lazily,
        initialValue = proxy.value
    )

    object : RealDataContainer<T>(result) {

        override suspend fun update(block: (T) -> T) {
            scope.launch {
                proxy.update(block)
            }
        }

        init {
            containers[T::class] = this as Datacontainer<T>
            result.launchIn(scope)
        }
    }
}

inline fun <reified T : Any> ContainerBuilder.container(crossinline block: DatacontainerBuilder<T>.() -> Unit) {

    var proxy: ObservableResource<T>
    var transforms: List<Transform<out Any, T>>
    var scope: CoroutineScope

    DatacontainerBuilder<T>().apply { block() }.also {
        proxy = it.proxy ?: error("Set observable resource of type <${T::class.simpleName}> or initial value")
        transforms = it.transforms
        scope = it.coroutineScope
    }

    val trasformed: Flow<T> = transforms.fold(proxy as Flow<T>) { acc, (flow, transform) ->
        flow().combine(acc, transform)
    }

    val result = trasformed.stateIn(
        scope = scope,
        started = SharingStarted.Lazily,
        initialValue = proxy.value
    )

    object : RealDataContainer<T>(result) {

        override suspend fun update(block: (T) -> T) {
            scope.launch {
                proxy.update(block)
            }
        }

        init {
            containers[T::class] = this as Datacontainer<T>
            result.launchIn(scope)
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
    var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)


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

    fun coroutineScope(block: () -> CoroutineScope) {
        coroutineScope = block()
    }

    inline fun <reified R : Any> ScopeBuilder.resource() {
        proxy = resources.resolveObserved<R>() as? ObservableResource<T> ?: error("This resource is not Observable<${R::class.simpleName}>")
    }
}
