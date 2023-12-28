package ru.alexey.event.threads.datacontainer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.alexey.event.threads.Builder
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.foldAndStateWithProxy
import ru.alexey.event.threads.foldAndStateWithProxyAndWatchers
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.flowResource
import kotlin.reflect.KClass


class ContainerBuilder {
    private val containers: MutableMap<KClass<out Any>, Datacontainer<out Any>>
            = mutableMapOf()
    val mutex = Mutex(true)

    operator fun<T: Any> set(kClass: KClass<T>, container: Datacontainer<T>) {
        containers[kClass] = container
    }
    operator fun<T: Any> get(kClass: KClass<T>): Datacontainer<T>?
        = containers[kClass] as? Datacontainer<T>

    @Builder
    inline fun <reified T : Any> container(initial: T) {
        val innerFlow = MutableStateFlow(initial)

        this[T::class] = object : RealDataContainer<T>(innerFlow) {
            override suspend fun update(block: (T) -> T) {
                innerFlow.update(block)
            }
        } as Datacontainer<T>
    }

    @Builder
    inline fun <reified T : Any> container(initial: T, block: DatacontainerBuilder<T>.() -> Unit) {

        val proxy: ObservableResource<T> = flowResource(initial)
        var transforms: List<Transform<out Any, T>>
        var scope: CoroutineScope
        var watchers: List<(T) -> Unit>

        DatacontainerBuilder(T::class).apply(block).build().also {
            transforms = it.transforms
            scope = it.coroutineScope
            watchers = it.watchers
        }

        realDataContainer(
            flow = transforms.foldAndStateWithProxyAndWatchers(proxy, watchers, scope),
            scope = scope
        ) {
            scope.launch {
                proxy.update(it)
            }
        }
    }

    @Builder
    inline fun <reified T : Any> container(block: DatacontainerBuilder<T>.() -> Unit) {

        var proxy: ObservableResource<T>
        var transforms: List<Transform<out Any, T>>
        var scope: CoroutineScope
        var watchers = listOf<(T) -> Unit>()

        //(resource(T::class) as? ObservableResource<T>)?.also { proxy = it }

        DatacontainerBuilder(T::class).apply { block() }.build().also {
            proxy = it.proxy ?: error("Set observable resource of type <${T::class.simpleName}> or initial value")
            transforms = it.transforms
            scope = it.coroutineScope
            watchers = it.watchers
        }

        realDataContainer(transforms.foldAndStateWithProxyAndWatchers(proxy, watchers, scope), scope) { it: (T) -> T ->
            scope.launch {
                proxy.update(it)
            }
        }
    }
}







data class Transform<Other : Any, T : Any>(
    val other: () -> Flow<Other>,
    val action: suspend (@UnsafeVariance Other, @UnsafeVariance T) -> T
)


interface DataContainerConfig<T: Any> {
    val transforms: List<Transform<out Any, T>>
    val proxy: ObservableResource<T>?
    val coroutineScope: CoroutineScope
    val watchers: List<(T) -> Unit>
}

class DatacontainerBuilder<T : Any>(private val clazz: KClass<T>) {

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

    fun watcher(watcher: (T) -> Unit) {
        watchers.add(watcher)
    }


    fun <Other : Any>ContainerBuilder.transform(clazz: KClass<Other>, block: suspend (Other, T) -> T) {
        val cb = this
        val t = Transform(
            other = {
                flow {
                    mutex.withLock {}
                    cb[clazz]?.let {
                        emitAll(it)
                    }
                }
            },
            action = block
        )
        transforms.add(t)
    }

    @Builder
    inline fun <reified Other : Any> ContainerBuilder.transform(noinline block: suspend (Other, T) -> T) {
        transform(Other::class, block)
    }

    fun coroutineScope(block: () -> CoroutineScope) {
        coroutineScope = block()
    }

    fun <R : Any> ScopeBuilder.resourceLoad(clazz: KClass<R>) {
        proxy = resource(clazz) as? ObservableResource<T> ?: error("This resource is not Observable<${clazz.simpleName}>")
    }

    @Builder
    inline fun <reified R : Any> ScopeBuilder.resource() {
        resourceLoad(R::class)
    }

    @Builder
    fun ScopeBuilder.bindToResource() {
        proxy = resource(clazz) as? ObservableResource<T> ?: error("This resource is not Observable<${clazz.simpleName}>")
    }
}
