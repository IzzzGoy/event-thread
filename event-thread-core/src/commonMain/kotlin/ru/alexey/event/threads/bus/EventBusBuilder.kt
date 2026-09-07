package ru.alexey.event.threads.bus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import ru.alexey.event.threads.Builder
import kotlin.reflect.KClass

class EventBussBuilder {
    private val interceptors = mutableListOf<Interceptor>()
    private val errorHandlers = mutableListOf<ErrorHandler>()
    private var coroutineScope: CoroutineScope? = null

    fun build(): EventBus = EventBus(coroutineScope ?: CoroutineScope(Dispatchers.Default), interceptors, errorHandlers)

    @Builder
    fun watcher(watcher: (Event) -> Unit) {
        interceptors.add(watcher)
    }

    @Builder
    fun<T: Event> typedWatcher(clazz: KClass<T>, watcher: suspend (T) -> Unit) {
        interceptors.add {
            if (clazz.isInstance(it)) {
                watcher(it as T)
            }
        }
    }
    @Builder
    inline fun<reified T: Event> typedWatcher(noinline watcher: suspend (T) -> Unit) {
        typedWatcher(T::class, watcher)
    }

    @Builder
    fun coroutineScope(block: () -> CoroutineScope) {
        coroutineScope = block()
    }

    /**
     * Registers a handler invoked whenever a watcher or a thread's action throws while
     * processing an event on this bus - see [ErrorHandler]/[EventBus] for why this exists.
     * Multiple registrations all run (in registration order); if none are registered, the bus
     * falls back to a diagnostic `println` so failures stay visible without crashing dispatch.
     */
    @Builder
    fun onError(handler: suspend (Event, Throwable) -> Unit) {
        errorHandlers.add(ErrorHandler(handler))
    }
}