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

        DatacontainerBuilder(T::class).apply(block).also {
            transforms = it.transforms
            scope = it.coroutineScope
        }

        realDataContainer(
            flow = transforms.foldAndStateWithProxy(proxy, scope),
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

        DatacontainerBuilder(T::class).apply { block() }.also {
            proxy = it.proxy ?: error("Set observable resource of type <${T::class.simpleName}> or initial value")
            transforms = it.transforms
            scope = it.coroutineScope
        }

        realDataContainer(transforms.foldAndStateWithProxy(proxy, scope), scope) { it: (T) -> T ->
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

class DatacontainerBuilder<T : Any>(private val clazz: KClass<T>) {

    val transforms = mutableListOf<Transform<out Any, T>>()

    var proxy: ObservableResource<T>? = null
    var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)


    @Builder
    inline fun <reified Other : Any> ContainerBuilder.transform(noinline block: suspend (Other, T) -> T) {
        val cb = this
        val t = Transform(
            other = {
                flow {
                    mutex.withLock {}
                    cb[Other::class]?.let {
                        emitAll(it)
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

    @Builder
    inline fun <reified R : Any> ScopeBuilder.resource() {
        proxy = resource(R::class) as? ObservableResource<T> ?: error("This resource is not Observable<${R::class.simpleName}>")
    }

    @Builder
    fun ScopeBuilder.bindToResource() {
        proxy = resource(clazz) as? ObservableResource<T> ?: error("This resource is not Observable<${clazz.simpleName}>")
    }
}
