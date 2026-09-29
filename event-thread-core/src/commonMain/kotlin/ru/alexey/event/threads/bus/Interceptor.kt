package ru.alexey.event.threads.bus

/**
 * A watcher registered via `config { createEventBus { watcher { event -> } } }` (or
 * [EventBusBuilder.typedWatcher]). Invoked for every [event] this bus actually has a `thread<T>()`
 * for, before that thread's own actions run - see [EventBus.dispatchToSubscribers] for exactly
 * which events qualify. An interceptor that throws is reported via [ErrorHandler] like any other
 * failure on the dispatch path; it does not stop the event's own actions from running.
 */
fun interface Interceptor {
    suspend operator fun invoke(event: Event)
}