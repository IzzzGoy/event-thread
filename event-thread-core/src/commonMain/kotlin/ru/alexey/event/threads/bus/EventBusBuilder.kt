package ru.alexey.event.threads.bus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import ru.alexey.event.threads.Builder
import kotlin.reflect.KClass

class EventBussBuilder {
    private val interceptors = mutableListOf<Interceptor>()
    private var coroutineScope: CoroutineScope? = null

    fun build(): EventBus = EventBus(coroutineScope ?: CoroutineScope(Dispatchers.Default), interceptors)

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
}