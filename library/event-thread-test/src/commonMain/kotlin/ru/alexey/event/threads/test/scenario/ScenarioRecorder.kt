package ru.alexey.event.threads.test.scenario

import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.EventBusBuilder
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

// Same lock-free copy-on-write retry loop as `ru.alexey.event.threads.utils.update`, which this
// module can't see (it's `internal` to event-thread-core) - kept private here rather than
// exposing/duplicating it as a public utility, since this is the only caller.
@OptIn(ExperimentalAtomicApi::class)
private fun <T> AtomicReference<T>.update(transform: (T) -> T) {
    while (true) {
        val current = load()
        if (compareAndSet(current, transform(current))) return
    }
}

/**
 * Records, per named scope, every event a registered `watcher { }` observed - i.e. every event
 * that actually matched one of that scope's `thread<T>()` registrations (see [ru.alexey.event.
 * threads.bus.Interceptor]'s KDoc for exactly which events qualify - unmatched/orphan events never
 * reach a watcher at all). Wire a recorder into a scope's own bus config with [recordInto]
 * (typically `config { createEventBus { recordInto(recorder, "ScopeName") } } }`), then read the
 * captured sequence back with [trace] - or, more conveniently, via `scenario(recorder) { scope(name)
 * { expectEvents(...) } }` ([ScenarioAssertionBuilder]) - once a scenario's dispatch has settled.
 *
 * **Why a watcher, not [ru.alexey.event.threads.bus.EventBus.output]:** an earlier version of this
 * recorder collected `output` instead, since it needs no config change to the scope under test.
 * That turned out to be genuinely flaky: any scope loaded through a [ru.alexey.event.threads.
 * scopeholder.ScopeHolder] always gets a second, real-`Dispatchers.Default`-backed collector
 * attached internally for external-event routing (see `ScopeHolder.loadInternal`), regardless of
 * whether external routing is actually configured. `output`'s buffer is only one slot deep
 * (`replay = 1`, no extra capacity), so a slow real collector can make a later `emit` suspend on
 * that real thread's progress - invisible to, and unresolvable by, a test's virtual
 * `TestDispatcher`. A `watcher { }` call is a plain, synchronous, in-process function invoked
 * directly inside the dispatch coroutine (see `EventBus.dispatchToSubscribers`) - no buffering, no
 * second consumer, no real-thread dependency - so it stays deterministic under
 * `advanceUntilIdle()`. The trade-off: the scope under test must register the watcher itself (one
 * extra line in its `config { }`), instead of a [ScenarioRecorder] attaching after the fact.
 *
 * **Ordering guarantee:** the sequence recorded *within one scope* is exactly dispatch order (a
 * watcher runs synchronously as each matching event is dispatched). The relative order of events
 * *across two different scopes* is deliberately not something this recorder (or [ScopeScenario])
 * promises anything about - [ru.alexey.event.threads.bus.EventBus]'s dispatch loop runs each
 * event's own actions in its own coroutine (see its KDoc), so two scopes' timelines can interleave
 * differently between runs even when every scope's bus shares one `TestDispatcher`. Assert
 * per-scope order only; if a test needs to pin a cross-scope effect, assert it structurally
 * instead (e.g. a downstream scope's [ru.alexey.event.threads.datacontainer.Datacontainer]
 * reaching an expected value at all, via [ScopeScenario.expectState]).
 */
@OptIn(ExperimentalAtomicApi::class)
class ScenarioRecorder {
    private val tracesRef = AtomicReference<Map<String, List<Event>>>(emptyMap())

    /** Appends [event] to [scopeName]'s recorded trace. Called from a `watcher { }` registered via
     * [recordInto] - not meant to be called directly from test code. */
    fun record(scopeName: String, event: Event) {
        tracesRef.update { current -> current + (scopeName to (current[scopeName].orEmpty() + event)) }
    }

    /** The events recorded for [scopeName] so far, in dispatch order - empty if nothing was ever
     * [record]ed for it (e.g. [recordInto] was never wired into that scope's config). */
    fun trace(scopeName: String): List<Event> = tracesRef.load()[scopeName].orEmpty()
}

/** Registers a `watcher { }` on this bus that reports every matched event to [recorder] under
 * [scopeName] - use inside `config { createEventBus { recordInto(recorder, "ScopeName") } } }`. */
fun EventBusBuilder.recordInto(recorder: ScenarioRecorder, scopeName: String) {
    watcher { event -> recorder.record(scopeName, event) }
}
