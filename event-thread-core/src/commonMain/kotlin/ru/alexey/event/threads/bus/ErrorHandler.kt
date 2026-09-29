package ru.alexey.event.threads.bus

/**
 * Registered via `config { createEventBus { onError { event, error -> } } }`. Invoked whenever a
 * watcher or an [EventThread] action throws while processing [event] - see [EventBus] for why
 * this exists and what happens when no handler is registered.
 */
fun interface ErrorHandler {
    suspend operator fun invoke(event: Event, error: Throwable)
}
