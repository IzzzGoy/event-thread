package ru.alexey.event.threads

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

interface Event

interface StrictEvent : Event
interface ExtendableEvent : Event

@OptIn(ExperimentalStdlibApi::class)
class EventBus(
    private val coroutineScope: CoroutineScope,
    private val watchers: List<suspend (Event) -> Unit>
): AutoCloseable {

    private val channel: Channel<Event> = Channel()
    private val subscribers: MutableMap<KClass<out Event>, EventThread<out Event>> = mutableMapOf()

    val metadata
        get () = subscribers.map { it.key.simpleName.orEmpty() to it.value.eventTypes }.toMap()

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
                    when (event) {
                        is StrictEvent -> {
                            subscribers[event::class]?.actions?.forEach {
                                launch {
                                    it(event)
                                }
                            }
                        }

                        is ExtendableEvent -> {
                            for ((key, value) in subscribers.entries) {
                                if (key.isInstance(event)) {
                                    value.actions.forEach { it(event) }
                                }
                            }
                        }

                        else -> {
                            subscribers[event::class]?.actions?.forEach {
                                launch {
                                    it(event)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    operator fun plus(event: Event) {
        coroutineScope.launch {
            channel.send(event)
        }
    }

    suspend fun send(event: Event) {
        channel.send(event)
    }

    operator fun<T> invoke(clazz: KClass<T>, action: () -> EventThread<T>) where T: Event {
        subscribers[clazz] = action()
    }

    inline operator fun<reified T> invoke(noinline action: () -> EventThread<T>) where T: Any, T: Event {
        invoke(T::class, action)
    }

    fun<T: Event> external(clazz: KClass<T>, action: suspend (Event) -> Unit) {
        subscribers.getOrPut(clazz) {
            object : EventThread<T>() {
                override fun close() {
                    unsubscribe(clazz)
                }

                init {
                    this@EventBus.invoke(clazz) { this }
                }
            }
        }.invoke(EventType.external, action)
    }

    fun collectToEventBus(events: Flow<Event>) {
        coroutineScope.launch {
            events.collect { event -> this@EventBus + event }
        }
    }

    inline fun<reified T: Event> external(noinline action: suspend (Event) -> Unit) = external(T::class, action)

    override fun close() {
        subscribers.values.forEach(EventThread<*>::close)
        coroutineScope.cancel()
    }

    companion object {
        fun defaultFactory(): EventBus {
            return EventBus(CoroutineScope(Dispatchers.Default), emptyList())
        }
    }
}

class EventBussBuilder {
    private val interceptors = mutableListOf<suspend (Event) -> Unit>()
    private var coroutineScope: CoroutineScope? = null

    fun build(): EventBus = EventBus(coroutineScope ?: CoroutineScope(Dispatchers.Default), interceptors)

    @Builder
    fun watcher(watcher: (Event) -> Unit) {
        interceptors.add(watcher)
    }

    @Builder
    fun<T: Event> errorWatcher(clazz: KClass<T>, watcher: suspend (T) -> Unit) {
        interceptors.add {
            if (clazz.isInstance(it)) {
                watcher(it as T)
            }
        }
    }
    @Builder
    inline fun<reified T: Event> errorWatcher(noinline watcher: suspend (T) -> Unit) {
        errorWatcher(T::class, watcher)
    }

    @Builder
    fun coroutineScope(block: () -> CoroutineScope) {
        coroutineScope = block()
    }
}
