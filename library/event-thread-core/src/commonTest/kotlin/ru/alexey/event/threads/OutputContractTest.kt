package ru.alexey.event.threads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.OutputContractViolationException
import ru.alexey.event.threads.bus.StrictEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private data object Trigger : StrictEvent
private data object AllowedOut : StrictEvent
private data object DisallowedOut : StrictEvent
private open class BaseOut : StrictEvent
private class SubOut : BaseOut()

/**
 * `thread<T> { output<X>() }` declares the events [T]'s own action chain may legitimately push
 * onto its own `eventBus` - both a cascading `.then { }`'s return value and any imperative
 * `eventBus += ` made directly inside a `.then { }`/`.end { }` body (see [ThreadActionScope]).
 * Covers all three [OutputMismatchBehavior]s plus the "nothing declared -> unrestricted" default.
 */
class OutputContractTest {

    private suspend fun <T> CompletableDeferred<T>.awaitWithTimeout(): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5000) { await() } }

    @Test
    fun undeclaredOutputMeansEveryPushIsAllowed() = runTest {
        val allowedHandled = CompletableDeferred<Unit>()

        val scope = scopeBuilder("no-output-declared") {
            threads {
                // No output<>() declared at all - exactly today's behavior, unrestricted.
                thread<Trigger>().then { AllowedOut }
                thread<AllowedOut>().end { allowedHandled.complete(Unit) }
            }
        }(emptyMap()).build()

        try {
            scope + Trigger
            allowedHandled.awaitWithTimeout()
        } finally {
            scope.close()
        }
    }

    @Test
    fun aPushMatchingTheDeclaredOutputIsDelivered() = runTest {
        val allowedHandled = CompletableDeferred<Unit>()

        val scope = scopeBuilder("matching-output") {
            threads {
                thread<Trigger> { output<AllowedOut>() }.then { AllowedOut }
                thread<AllowedOut>().end { allowedHandled.complete(Unit) }
            }
        }(emptyMap()).build()

        try {
            scope + Trigger
            allowedHandled.awaitWithTimeout()
        } finally {
            scope.close()
        }
    }

    @Test
    fun strictMismatchThrowsReportsViaOnErrorAndStopsTheChain() = runTest {
        val errorSeen = CompletableDeferred<Pair<Event, Throwable>>()
        val afterCascade = CompletableDeferred<Unit>()

        val scope = scopeBuilder("strict-mismatch") {
            config {
                createEventBus {
                    onError { event, error -> errorSeen.complete(event to error) }
                }
            }
            threads {
                // onMismatch defaults to Strict.
                thread<Trigger> { output<AllowedOut>() }
                    .then { DisallowedOut }
                    .end { afterCascade.complete(Unit) }
            }
        }(emptyMap()).build()

        try {
            scope + Trigger
            val (event, error) = errorSeen.awaitWithTimeout()

            assertEquals(Trigger, event)
            assertIs<OutputContractViolationException>(error)

            // Strict throws from inside the cascade step, which EventBus.runActions treats like
            // any other action failure - it stops the rest of *this* thread's chain, so the
            // later .end step must never run.
            withContext(Dispatchers.Default) { delay(150) }
            assertTrue(!afterCascade.isCompleted)
        } finally {
            scope.close()
        }
    }

    @Test
    fun warningMismatchReportsViaOnErrorButLetsTheChainContinue() = runTest {
        val errorSeen = CompletableDeferred<Pair<Event, Throwable>>()
        val afterCascade = CompletableDeferred<Unit>()

        val scope = scopeBuilder("warning-mismatch") {
            config {
                createEventBus {
                    onError { event, error -> errorSeen.complete(event to error) }
                }
            }
            threads {
                thread<Trigger> {
                    output<AllowedOut>()
                    onMismatch(OutputMismatchBehavior.Warning)
                }
                    .then { DisallowedOut }
                    .end { afterCascade.complete(Unit) }
            }
        }(emptyMap()).build()

        try {
            scope + Trigger
            val (event, error) = errorSeen.awaitWithTimeout()
            assertEquals(Trigger, event)
            assertIs<OutputContractViolationException>(error)

            // Warning doesn't throw - the rejected push just doesn't happen, the rest of this
            // thread's chain still runs normally.
            afterCascade.awaitWithTimeout()
        } finally {
            scope.close()
        }
    }

    @Test
    fun silentMismatchNeitherThrowsNorReportsButStillDropsTheEvent() = runTest {
        val errorSeen = CompletableDeferred<Pair<Event, Throwable>>()
        val afterCascade = CompletableDeferred<Unit>()
        val disallowedHandled = CompletableDeferred<Unit>()

        val scope = scopeBuilder("silent-mismatch") {
            config {
                createEventBus {
                    onError { event, error -> errorSeen.complete(event to error) }
                }
            }
            threads {
                thread<Trigger> {
                    output<AllowedOut>()
                    onMismatch(OutputMismatchBehavior.Silent)
                }
                    .then { DisallowedOut }
                    .end { afterCascade.complete(Unit) }
                thread<DisallowedOut>().end { disallowedHandled.complete(Unit) }
            }
        }(emptyMap()).build()

        try {
            scope + Trigger
            // Chain continues (no throw)...
            afterCascade.awaitWithTimeout()

            // ...but the rejected event is still never delivered, and nothing is reported.
            withContext(Dispatchers.Default) { delay(150) }
            assertTrue(!errorSeen.isCompleted)
            assertTrue(!disallowedHandled.isCompleted)
        } finally {
            scope.close()
        }
    }

    @Test
    fun anImperativePushInsideEndIsValidatedTheSameAsACascadeReturn() = runTest {
        val errorSeen = CompletableDeferred<Pair<Event, Throwable>>()

        val scope = scopeBuilder("imperative-push") {
            config {
                createEventBus {
                    onError { event, error -> errorSeen.complete(event to error) }
                }
            }
            threads {
                thread<Trigger> { output<AllowedOut>() }.end {
                    eventBus += DisallowedOut
                }
            }
        }(emptyMap()).build()

        try {
            scope + Trigger
            val (event, error) = errorSeen.awaitWithTimeout()
            assertEquals(Trigger, event)
            assertIs<OutputContractViolationException>(error)
        } finally {
            scope.close()
        }
    }

    @Test
    fun declaringABaseTypeAllowsPushingASubtypeOfIt() = runTest {
        val subHandled = CompletableDeferred<Unit>()

        val scope = scopeBuilder("subtype-output") {
            threads {
                thread<Trigger> { output<BaseOut>() }.end {
                    eventBus += SubOut()
                }
                thread<SubOut>().end { subHandled.complete(Unit) }
            }
        }(emptyMap()).build()

        try {
            scope + Trigger
            subHandled.awaitWithTimeout()
        } finally {
            scope.close()
        }
    }
}
