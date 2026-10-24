package ru.alexey.event.threads.utils

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

// Lock-free copy-on-write update: retries [transform] against a fresh snapshot until the CAS
// succeeds, so a concurrent mutation from another thread is never silently lost or torn. Used
// wherever a small, infrequently-mutated collection needs to stay safe to read from a different
// thread than the one mutating it, without locking the (much hotter) read side - e.g.
// `ScopeHolder`'s active-scope/routing-job registries and `EventBus`'s subscriber maps.
@OptIn(ExperimentalAtomicApi::class)
internal fun <T> AtomicReference<T>.update(transform: (T) -> T) {
    while (true) {
        val current = load()
        if (compareAndSet(current, transform(current))) return
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal fun <K, V> AtomicReference<Map<K, V>>.removeAndGet(key: K): V? {
    while (true) {
        val current = load()
        val removed = current[key] ?: return null
        if (compareAndSet(current, current - key)) return removed
    }
}
