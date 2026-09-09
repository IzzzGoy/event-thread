package ru.alexey.event.threads.bus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import ru.alexey.event.threads.Builder
import kotlin.reflect.KClass

/** Receiver for `config { createEventBus { } }` - configures the [EventBus] a [ru.alexey.event.
 * threads.Scope] uses: watchers, error handlers, and which [CoroutineScope] the bus's dispatch
 * loop runs on. Used via [ru.alexey.event.threads.ConfigBuilder.createEventBus], not directly. */
class EventBusBuilder {
    private val interceptors = mutableListOf<Interceptor>()
    private val errorHandlers = mutableListOf<ErrorHandler>()
    private var coroutineScope: CoroutineScope? = null

    /** Builds the [EventBus]. Defaults to a fresh `CoroutineScope(Dispatchers.Default)` parent
     * scope when [coroutineScope] was never called. */
    fun build(): EventBus = EventBus(coroutineScope ?: CoroutineScope(Dispatchers.Default), interceptors, errorHandlers)

    /** Registers an untyped [Interceptor] invoked for every event this bus handles. */
    @Builder
    fun watcher(watcher: (Event) -> Unit) {
        interceptors.add(watcher)
    }

    /** [watcher] filtered to instances of [clazz] - other events are ignored. */
    @Builder
    fun<T: Event> typedWatcher(clazz: KClass<T>, watcher: suspend (T) -> Unit) {
        interceptors.add {
            if (clazz.isInstance(it)) {
                watcher(it as T)
            }
        }
    }
    /** [typedWatcher] with [T] inferred from the reified type parameter instead of passed
     * explicitly. */
    @Builder
    inline fun<reified T: Event> typedWatcher(noinline watcher: suspend (T) -> Unit) {
        typedWatcher(T::class, watcher)
    }

    /** Sets the parent [CoroutineScope] this bus's dispatch loop runs as a supervised child of -
     * see [EventBus]'s own KDoc for why it's a child rather than the scope itself. Defaults to
     * `CoroutineScope(Dispatchers.Default)` if never called. */
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