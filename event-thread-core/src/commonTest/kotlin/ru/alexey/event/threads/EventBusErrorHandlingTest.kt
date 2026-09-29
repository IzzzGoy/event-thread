package ru.alexey.event.threads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.StrictEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data object FailingEvent : StrictEvent
data object OkEvent : StrictEvent

/**
 * Before `onError`/`ErrorHandler` (see `EventBus.runActions`/`notifyError`), a thread action
 * throwing propagated out of the unsupervised `launch { dispatch(event) }` in `EventBus.init`,
 * cancelling that bus's whole reader-loop coroutine - one bad handler silently killed all future
 * dispatch for the scope. This test pins the fix: a later, unrelated event must still be handled.
 *
 * `FailingEvent` and `OkEvent` dispatch on independent `launch{}` calls (one per event, see
 * `EventBus.init`), so there's no ordering guarantee between them - both completions are awaited
 * explicitly rather than assuming one implies the other already ran.
 */
class EventBusErrorHandlingTest {

    @Test
    fun actionThrowingDoesNotKillLaterDispatch() = runTest {
        val errorSeen = CompletableDeferred<Pair<Event, Throwable>>()
        val okHandled = CompletableDeferred<Unit>()

        val scope = scopeBuilder("error-handling-test") {
            config {
                createEventBus {
                    onError { event, error -> errorSeen.complete(event to error) }
                }
            }

            threads {
                thread<FailingEvent>().end {
                    error("boom")
                }
                thread<OkEvent>().end {
                    okHandled.complete(Unit)
                }
            }
        }

        val builtScope = scope(emptyMap()).build()
        try {
            builtScope + FailingEvent
            builtScope + OkEvent

            // real Dispatchers.Default coroutines under the hood, not runTest's virtual clock -
            // await from a real dispatcher so withTimeout measures wall time, not virtual time.
            val (failedEvent, error) = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5000) { errorSeen.await() }
            }
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5000) { okHandled.await() }
            }

            assertEquals(FailingEvent, failedEvent)
            assertTrue(error is IllegalStateException)
        } finally {
            builtScope.close()
        }
    }
}
