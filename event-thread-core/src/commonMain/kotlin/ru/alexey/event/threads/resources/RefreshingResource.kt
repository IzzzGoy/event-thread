package ru.alexey.event.threads.resources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * A [FlowResource] refreshed by [fetch] every time [trigger] emits, plus once immediately on
 * construction - purely in-memory, no durable side at all.
 * Meant for things like a remote config or feature flags: [trigger] can be a fixed-interval
 * ticker, but it doesn't have to be - a reconnect signal, a lifecycle callback, a manual
 * "refresh now" button press all work the same way. [ObservableResource.update] on the result
 * stays local-only (matching plain [FlowResource]) - [trigger] is still the only thing that
 * causes a re-fetch.
 *
 * A failed [fetch] (including the initial one) is swallowed, leaving the last good value (or
 * [initial], if nothing has ever succeeded) in place rather than surfacing an error state -
 * transient network failures shouldn't blank out an already-loaded config.
 */
fun <T : Any> refreshingResource(
    initial: T,
    scope: CoroutineScope,
    trigger: Flow<Unit>,
    fetch: suspend () -> T
): ObservableResource<T> {
    val resource = FlowResource(MutableStateFlow(initial))

    scope.launch {
        runCatching { fetch() }.onSuccess { resource.set(it) }
        trigger.collect {
            runCatching { fetch() }.onSuccess { resource.set(it) }
        }
    }

    return resource
}
