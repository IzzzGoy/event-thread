package ru.alexey.event.threads.scopeholder.typed

/**
 * The state of data loaded from somewhere remote - what a [EventHandler.Request] writes into its
 * container. Every state keeps the last loaded value it can ([valueOrNull]), so a reload or a
 * failed refresh doesn't blank the screen:
 *
 * ```kotlin
 * when (val t = todos) {
 *     RemoteData.Idle -> {}
 *     is RemoteData.Loading -> Spinner(t.previous)
 *     is RemoteData.Success -> TodoList(t.value)
 *     is RemoteData.Failure -> ErrorView(t.error, t.previous, retry = { send(Refresh) })
 * }
 * ```
 */
sealed interface RemoteData<out T> {

    /** Nothing requested yet. */
    data object Idle : RemoteData<Nothing>

    /** A request is running; [previous] is the last loaded value, if any. */
    data class Loading<out T>(val previous: T? = null) : RemoteData<T>

    data class Success<out T>(val value: T) : RemoteData<T>

    /** The last request failed with [error]; [previous] is the last loaded value, if any. */
    data class Failure<out T>(val error: Throwable, val previous: T? = null) : RemoteData<T>

    /** The current value if loaded, else the last loaded one, else `null`. */
    val valueOrNull: T?
        get() = when (this) {
            Idle -> null
            is Loading -> previous
            is Success -> value
            is Failure -> previous
        }

    val isLoading: Boolean get() = this is Loading
}

/** How a [EventHandler.Request] reacts to a trigger while one is running, and how hard it tries. */
data class RequestPolicy(
    val concurrency: Concurrency = Concurrency.Latest,
    /** Extra attempts after a failure (not after cancellation). */
    val retry: Int = 0,
    /** Delay before the first retry; doubles for each further one. */
    val retryDelayMs: Long = 1000,
    /** Per attempt; `null`: none. A timeout is a failure like any other (and is retried). */
    val timeoutMs: Long? = null,
    /** Waits this long before calling; a trigger arriving meanwhile replaces the pending one
     * (with [Concurrency.Latest] - the usual search-as-you-type debounce). */
    val debounceMs: Long = 0,
) {
    init {
        require(retry >= 0) { "retry must be >= 0" }
        require(retryDelayMs >= 0 && debounceMs >= 0) { "delays must be >= 0" }
        require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be > 0" }
    }

    enum class Concurrency {
        /** A new trigger cancels the running request - refresh, search. "New" is by the order
         * the scope received them: an older trigger arriving late never replaces a newer one. */
        Latest,
        /** A trigger while one is running is ignored - "load more", a button pressed twice. */
        Drop,
        /** Triggers run one after another, in the order they were sent - saving edits. Like any
         * handler's, triggers sent at the very same instant (dispatched concurrently) may still
         * reach it in either order. */
        Queue,
    }
}

/** A request attempt that didn't finish within [RequestPolicy.timeoutMs]. */
class RequestTimeoutException(val timeoutMs: Long) : RuntimeException("Request timed out after ${timeoutMs}ms")
