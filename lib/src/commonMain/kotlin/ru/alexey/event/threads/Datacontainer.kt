package ru.alexey.event.threads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

interface Datacontainer<T> : StateFlow<T> {
    fun update(block: (T) -> T)
}

abstract class RealDataContainer<T>(
    stateFlow: StateFlow<T>,
    
): StateFlow<T> by stateFlow, Datacontainer<T>

inline fun<reified T: Any> ConfigBuilder.container(initial: T){
    
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

inline fun<reified T: Any> ConfigBuilder.container(initial: T, block: DatacontainerBuilder<T>.() -> Unit) {

    val innerFlow = MutableStateFlow(initial)

    val trasformed: Flow<T> = DatacontainerBuilder<T>().apply(block).transforms.fold(innerFlow as Flow<T>) { acc, (flow, transform) ->
        flow().combine(acc, transform)
    }

    val result = trasformed.stateIn(
        scope = CoroutineScope(Dispatchers.Default),
        started = SharingStarted.Lazily,
        initialValue = initial
    )

    object : RealDataContainer<T>(result) {

        override fun update(block: (T) -> T) {
            innerFlow.update(block)
        }
        init {
            containers.put(T::class, this as Datacontainer<T>)
        }
    }
}


data class Transform<Other: Any, T: Any>(
    val other: () -> Flow<Other>,
    val action: suspend (@UnsafeVariance Other, @UnsafeVariance T) -> T
)

class DatacontainerBuilder<T: Any> {

    val transforms = mutableListOf<Transform<out Any, T>>()



    inline fun<reified Other: Any> ConfigBuilder.transform( noinline block: suspend (Other, T) -> T) {
        val t = Transform(
            other = {
                flow {
                    channel.withLock {}
                    containers.get(Other::class)?.let {
                        emitAll(it as Datacontainer<Other>)
                    }
                }
            },
            action = block
        )
        transforms.add(t)
    }
}