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
import ru.alexey.event.threads.utils.update
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

/** Marker for anything dispatchable through an [EventBus]. */
interface Event

/**
 * An [Event] dispatched to exactly its own registered `thread<T>()`, not to handlers registered
 * for a supertype - see [EventBus.dispatchToSubscribers]'s `is StrictEvent` branch. Prefer this
 * over a plain [Event] unless you specifically need supertype-based (`isInstance`) matching.
 */
interface StrictEvent : Event

/** An [Event] dispatched to every registered `thread<T>()` whose type `isInstance` of it - so a
 * handler registered for a supertype also receives subtypes. See [StrictEvent] for the
 * exact-type-only alternative. */
interface ExtendableEvent : Event

/** The public contract [EventBus] implements - see [EventBus] for the concrete behavior/
 * thread-safety guarantees. */
interface IEventBus {
    /** Every event this bus has processed (via [dispatchToSubscribers]/`+=`), replayed once to a
     * late collector - see [EventBus._output]'s KDoc for why replay = 1. */
    val output: SharedFlow<Event>

    /** This bus's registered event threads, keyed by event class name - see [EventBus.metadata]. */
    val metadata: Map<String, EventThreadInfo>

    /** Dispatches [event] onto this bus. Thread-safe; does not suspend. */
    operator fun plusAssign(event: Event)

    /** Feeds every value of [events] into this bus as if dispatched via [plusAssign], for the
     * lifetime of the bus (or until [events] completes). See [EventBus.collectToEventBus] for
     * failure handling. */
    fun collectToEventBus(events: Flow<Event>)
}

private data object EmitterFailure : Event

/**
 * **Thread-safety:** [plusAssign]/`+=` and [collectToEventBus] are safe to call from any thread -
 * `Channel.trySend` is inherently thread-safe. The subscriber registry ([Scope.thread]-backed) is
 * also safe to mutate concurrently with dispatch: it's a lock-free copy-on-write snapshot (see
 * [ru.alexey.event.threads.utils.update]) specifically because `Scope.thread<T>()` is a public,
 * ordinary function - nothing stops an app from registering a new handler on a live scope from a
 * background thread while events are already flowing through it. What is NOT synchronized here:
 * whatever an individual action/watcher does internally with its own state - that's the
 * registering code's own responsibility, same as any other coroutine-based callback.
 */
@OptIn(ExperimentalStdlibApi::class, ExperimentalAtomicApi::class)
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

    // Unlimited, not a bounded buffer: a bounded channel's `trySend` starts failing once full,
    // and the old fallback for that (`coroutineScope.launch { channel.send(event) }` per rejected
    // event) meant a sustained producer/consumer imbalance grew the coroutine count without
    // bound - each one far heavier than the `Event` reference it's holding onto. An unbounded
    // channel turns the same "producer outruns the dispatch loop" scenario into an ordinary
    // growing queue of event references instead, which is strictly cheaper - it doesn't remove
    // the underlying problem (nothing can, short of the producer slowing down or `+=` becoming
    // suspending - it's a plain operator, so no in-band backpressure is possible), it just makes
    // sustained overload degrade in memory instead of in coroutine-scheduler overhead.
    private val channel: Channel<Event> = Channel(Channel.UNLIMITED)
    private val subscribersRef = AtomicReference<Map<KClass<out Event>, EventThread<out Event>>>(emptyMap())
    private val mergedSubscribersRef = AtomicReference<Map<KClass<out Event>, List<EventThread<out Event>>>>(emptyMap())
    private val subscribers: Map<KClass<out Event>, EventThread<out Event>> get() = subscribersRef.load()
    private val mergedSubscribers: Map<KClass<out Event>, List<EventThread<out Event>>> get() = mergedSubscribersRef.load()

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
        // trySend is synchronous, non-suspending and thread-safe. With an unlimited channel it
        // only ever fails once this bus has been `close()`d (the channel itself is closed) - in
        // that case there's no reader left to deliver to, so drop the event instead of launching
        // a coroutine doomed to throw `ClosedSendChannelException` trying to send into it.
        val result = channel.trySend(event)
        if (result.isFailure && !result.isClosed) {
            coroutineScope.launch {
                channel.send(event)
            }
        }
    }

    @PublishedApi
    internal operator fun<T> invoke(clazz: KClass<T>, action: () -> EventThread<T>) where T: Event {
        val thread = action()
        if (thread.eventMetadatas.metadata.override || subscribersRef.load()[clazz] == null) {
            subscribersRef.update { it + (clazz to thread) }
        } else {
            mergedSubscribersRef.update { current -> current + (clazz to (current[clazz].orEmpty() + thread)) }
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
