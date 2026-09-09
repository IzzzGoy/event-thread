@file:Suppress("UNCHECKED_CAST")

package ru.alexey.event.threads.bus

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.alexey.event.threads.EventThread
import ru.alexey.event.threads.EventThreadInfo
import kotlin.reflect.KClass

interface Event

interface StrictEvent : Event
interface ExtendableEvent : Event

interface IEventBus {
    val output: SharedFlow<Event>
    val metadata: Map<String, EventThreadInfo>

    operator fun plusAssign(event: Event)

    fun collectToEventBus(events: Flow<Event>)
}

private data object EmitterFailure : Event

@OptIn(ExperimentalStdlibApi::class)
class EventBus(
    parentScope: CoroutineScope,
    private val watchers: List<Interceptor>,
    private val errorHandlers: List<ErrorHandler> = emptyList()
): AutoCloseable, IEventBus {

    // A supervised child of the caller-provided scope, not the scope itself: `close()` below
    // cancels only this bus's own work. Cancelling `parentScope` directly (the old behavior)
    // would cancel whatever the caller passed in via `.coroutineScope { }` in its *entirety* -
    // e.g. their own `viewModelScope` - taking down everything else backed by that scope, not
    // just this bus. The child's own SupervisorJob additionally means the reader loop below and
    // the emitter collector in [collectToEventBus] can't cancel each other by failing.
    private val coroutineScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
    )

    private val channel: Channel<Event> = Channel(Channel.BUFFERED)
    private val subscribers: MutableMap<KClass<out Event>, EventThread<out Event>> = mutableMapOf()
    private val mergedSubscribers: MutableMap<KClass<out Event>, MutableList<EventThread<out Event>>> = mutableMapOf()

    // replay = 1, not 0: a plain zero-buffer SharedFlow delivers an emitted value only to
    // whoever is *already* collecting at that exact moment - with zero subscribers it's just
    // dropped, not queued. `ScopeHolder`'s external-routing collector attaches via a *separate*
    // `launchIn` call after a scope is built (see loadInternal), so there's a real window where
    // an event dispatched right after load finishes before that collector has actually started;
    // without replay, that event silently never reaches external routing. `launchIn` collects
    // strictly *after* subscribing, so the collector still sees every later emission exactly
    // once - replay only backfills the one case where it started collecting a beat too late.
    private val _output = MutableSharedFlow<Event>(replay = 1)
    override val output = _output.asSharedFlow()

    override val metadata
        get () = subscribers.map { it.key.simpleName.orEmpty() to it.value.eventMetadatas }.toMap()

    /**
     * Actions come from the primary registration plus any later-merged registrations for
     * [clazz], read live (not snapshotted) so actions chained onto a thread after it was
     * registered (the standard `thread<T>().end { }` DSL pattern) are still picked up.
     */
    private fun actionsFor(thread: EventThread<out Event>?, clazz: KClass<out Event>): List<suspend (Event) -> Unit> {
        val primary = thread?.actions ?: emptyList()
        val merged = mergedSubscribers[clazz]
        return if (merged.isNullOrEmpty()) primary else primary + merged.flatMap { it.actions }
    }

    private suspend fun dispatchToSubscribers(event: Event) {
        // Watchers only see events this bus actually has a thread<T>() for. Every active scope
        // used to get every broadcast event (see ScopeHolder.plus), so a watcher on one scope
        // silently observed traffic meant for every other mounted scope, not just its own -
        // this keeps that decision local to the bus instead of pushing it up to ScopeHolder.
        //
        // Computed once here and reused below for the non-strict dispatch loop, instead of
        // scanning `subscribers` with `isInstance` twice per event (once via the old `handles()`
        // call, once again to find which keys to run actions for).
        val matchedKeys = subscribers.keys.filter { it.isInstance(event) }
        if (matchedKeys.isEmpty()) return

        runGuarded(event) { watchers.forEach { it(event) } }

        when (event) {
            is StrictEvent -> {
                runActions(event, actionsFor(subscribers[event::class], event::class))
            }

            else -> {
                for (key in matchedKeys) {
                    runActions(event, actionsFor(subscribers[key], key))
                }
            }
        }
    }

    /**
     * Runs [actions] for [event] one at a time, same as before, but an action throwing no
     * longer propagates out - it would otherwise cross the `launch { dispatch(event) }` in
     * [init], and since that launch is a plain (non-supervisor) child of [coroutineScope], an
     * uncaught exception there cancels the whole scope's reader loop, silently killing all
     * future dispatch for this bus over one failed handler. Instead: report it via
     * [errorHandlers] (or a diagnostic println if none are registered - staying silent by
     * default would just trade "kills the bus" for "swallows the bug"), then stop *this*
     * subscriber's remaining chained actions (they're a sequential pipeline that assumed the
     * failed step succeeded) without affecting other subscribers or later events.
     */
    private suspend fun runActions(event: Event, actions: List<suspend (Event) -> Unit>) {
        for (action in actions) {
            try {
                action(event)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                notifyError(event, t)
                break
            }
        }
    }

    private suspend fun runGuarded(event: Event, block: suspend () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            notifyError(event, t)
        }
    }

    private suspend fun notifyError(event: Event, error: Throwable) {
        if (errorHandlers.isEmpty()) {
            println("EventBus: unhandled exception while processing $event: $error")
            return
        }
        errorHandlers.forEach { handler ->
            try {
                handler(event, error)
            } catch (c: CancellationException) {
                throw c
            } catch (handlerError: Throwable) {
                println("EventBus: onError handler itself threw while processing $event: $handlerError")
            }
        }
    }

    private suspend fun dispatch(event: Event) {
        dispatchToSubscribers(event)
        _output.emit(event)
    }

    /**
     * Delivers [event] to this bus's own subscribers without re-emitting it to [output].
     * Used by `ScopeHolder`'s external-event routing to deliver an event to its configured
     * receivers exactly once: since every bus's [output] unconditionally re-emits everything it
     * processes, routing a delivery back through the normal [plusAssign]/[dispatch] path would
     * make the receiver's own re-emission look like a brand new event eligible for another
     * round of routing - bouncing the same event between any two scopes that are both
     * configured as receivers for it, forever.
     */
    internal suspend fun deliverExternally(event: Event) {
        dispatchToSubscribers(event)
    }

    init {
        coroutineScope.launch {
            for (event in channel) {
                // One coroutine per event, not one per (watchers/dispatch/output) - dispatch of
                // different events still runs concurrently (this launch doesn't block the loop
                // from picking up the next event), but the three steps within a single event
                // now run in a fixed, deterministic order instead of racing each other.
                launch { dispatch(event) }
            }
        }
    }

    override operator fun plusAssign(event: Event) {
        // trySend is synchronous and non-suspending - with the default buffered channel this
        // succeeds immediately without spawning a coroutine. Only fall back to a suspending
        // send (which needs its own coroutine) when the buffer is actually full.
        if (channel.trySend(event).isFailure) {
            coroutineScope.launch {
                channel.send(event)
            }
        }
    }

    @PublishedApi
    internal operator fun<T> invoke(clazz: KClass<T>, action: () -> EventThread<T>) where T: Event {
        val thread = action()
        if (thread.eventMetadatas.metadata.override || subscribers[clazz] == null) {
            subscribers[clazz] = thread
        } else {
            mergedSubscribers.getOrPut(clazz) { mutableListOf() }.add(thread)
        }
    }

    @PublishedApi
    internal inline operator fun<reified T> invoke(noinline action: () -> EventThread<T>) where T: Any, T: Event {
        invoke(T::class, action)
    }

    override fun collectToEventBus(events: Flow<Event>) {
        coroutineScope.launch {
            try {
                events.collect { event -> this@EventBus += event }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Previously uncaught: an emitter Flow throwing here crossed into the platform's
                // default uncaught-exception handler (a process crash on Android/iOS, a stray
                // stderr trace with a now-dead collector on JVM/JS) instead of going through
                // [errorHandlers] like every other failure path in this class.
                notifyError(EmitterFailure, t)
            }
        }
    }

    override fun close() {
        coroutineScope.cancel()
    }

    companion object {
        fun defaultFactory(): EventBus {
            return EventBus(CoroutineScope(Dispatchers.Default), emptyList())
        }
    }
}
