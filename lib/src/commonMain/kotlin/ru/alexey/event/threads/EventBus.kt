package ru.alexey.event.threads

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.reflect.KClass


interface Event

@OptIn(ExperimentalStdlibApi::class)
class EventBus(
    private val watchers: List<(Event) -> Unit>
): AutoCloseable {
    
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val channel: Channel<Event> = Channel()
    private val subscribers: MutableMap<KClass<out Event>, EventThread<out Event>> = mutableMapOf()
    

    fun unsubscribe(clazz: KClass<out Event>) {
        subscribers.remove(clazz)
    }
    
    inline fun<reified T: Event> unsubscribe() {
        unsubscribe(T::class)
    }
    
    inline fun<reified T: Event> T.unsubscribe() {
        unsubscribe(this::class)
    }
    
    init {
        coroutineScope.launch {
            for (event in channel) {
                launch {
                    watchers.forEach { it(event) }
                }
                launch {
                    subscribers.values.forEach {
                        it.actions.forEach {
                            it(event)
                        }
                    }
                }
            }
        }
    }
    
    operator fun plus(event: Event) {
        channel.trySend(event)
    }
    
    suspend fun send(event: Event) {
        channel.send(event)
    } 
    
    operator fun<T> invoke(clazz: KClass<T>, action: () -> EventThread<T>) where T: Any, T: Event {
        subscribers[clazz] = action()
    }
    
    inline operator fun<reified T> invoke(noinline action: () -> EventThread<T>) where T: Any, T: Event {
        invoke(T::class, action)
    }

    override fun close() {
        subscribers.values.forEach(EventThread<*>::close)
        coroutineScope.cancel()
    }

    companion object {
        fun defaultFactory(): EventBus {
            return EventBus(emptyList())
        }
    }
}

class EventBussBuilder {
    private val interceptors = mutableListOf<(Event) -> Unit>()

    fun build(): EventBus = EventBus(interceptors)

    fun watcher(watcher: (Event) -> Unit) {
        interceptors.add(watcher)
    }
}

fun ConfigBuilder.createEventBus(block: EventBussBuilder.() -> Unit) {
    eventBus = EventBussBuilder().also(block).build()
}