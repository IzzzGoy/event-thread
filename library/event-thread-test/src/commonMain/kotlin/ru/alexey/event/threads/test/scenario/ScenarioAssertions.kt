package ru.alexey.event.threads.test.scenario

import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.datacontainer.Datacontainer

/** Thrown by [ScopeScenario]'s assertions on a mismatch - framework-agnostic (not a `kotlin.test`
 * assertion), same convention as [ru.alexey.event.threads.test.graph.EventGraphValidationException]. */
class ScenarioValidationException(message: String) : IllegalStateException(message)

/**
 * Entry point for asserting against a [ScenarioRecorder]'s captured traces:
 * ```
 * scenario(recorder) {
 *     scope("OrderPipeline") {
 *         expectEvents("OrderPlaced", "OrderValidated", "PaymentCharged")
 *         expectState(total, 42)
 *     }
 * }
 * ```
 * Call once the scenario's dispatch has fully settled (e.g. after `advanceUntilIdle()` under
 * `runTest { }`, with every scope under test sharing one `TestDispatcher` - see [ScenarioRecorder]'s
 * KDoc) - see [ScopeScenario.expectState] for what "settled" needs to mean for it to be meaningful.
 */
fun scenario(recorder: ScenarioRecorder, block: ScenarioAssertionBuilder.() -> Unit) {
    ScenarioAssertionBuilder(recorder).apply(block)
}

/** Receiver for [scenario] - scopes assertions to one recorded scope at a time via [scope]. */
class ScenarioAssertionBuilder internal constructor(private val recorder: ScenarioRecorder) {
    /** Runs [block] against [scopeName]'s recorded trace - see [ScopeScenario]. */
    fun scope(scopeName: String, block: ScopeScenario.() -> Unit) {
        ScopeScenario(scopeName, recorder.trace(scopeName)).apply(block)
    }
}

/** Assertions against one scope's recorded runtime trace, scoped by [ScenarioAssertionBuilder.
 * scope] - see [ScenarioRecorder] for exactly what's captured and its ordering guarantees. */
class ScopeScenario internal constructor(private val scopeName: String, private val trace: List<Event>) {

    /**
     * Asserts [trace] is exactly this sequence of event *type* names, in order - e.g.
     * `expectEvents("OrderPlaced", "OrderValidated", "PaymentCharged")`. Fails with the whole
     * actual-vs-expected trace on any mismatch (wrong event, wrong order, extra or missing
     * events), not just the first broken position - a scenario mismatch is usually easier to
     * debug seeing the whole recorded run at once, unlike [ru.alexey.event.threads.test.graph.
     * ScopeFlow.reaches]'s hop-by-hop static check.
     */
    fun expectEvents(vararg expected: String) {
        val actual = trace.map { it::class.simpleName.orEmpty() }
        val expectedList = expected.toList()
        if (actual != expectedList) {
            throw ScenarioValidationException(
                "Scenario mismatch for scope '$scopeName':\n" +
                    "  expected: $expectedList\n" +
                    "  actual:   $actual"
            )
        }
    }

    /**
     * Asserts [container]'s current value equals [expected]. Call only once a scenario's
     * dispatched events have fully settled (e.g. after `advanceUntilIdle()`) - a [Datacontainer]
     * is a live [kotlinx.coroutines.flow.StateFlow], so reading `.value` mid-flight would just
     * race the update this is meant to verify.
     *
     * There's deliberately no "expected sequence of states" variant: `StateFlow` conflates rapid
     * updates by contract (only the latest value survives between collector resumptions), so
     * pinning an exact intermediate-states pattern would be asserting a guarantee the container
     * itself doesn't make - assert the settled end state instead.
     */
    fun <T> expectState(container: Datacontainer<T>, expected: T) {
        val actual = container.value
        if (actual != expected) {
            throw ScenarioValidationException(
                "Scenario mismatch for scope '$scopeName': expected state $expected, was $actual"
            )
        }
    }

    /** [expectState] with a [predicate] instead of a single exact value, for when the expected
     * state isn't practical to spell out verbatim (e.g. it embeds a timestamp or generated id).
     * [description] is used only in the failure message. */
    fun <T> expectState(container: Datacontainer<T>, description: String, predicate: (T) -> Boolean) {
        val actual = container.value
        if (!predicate(actual)) {
            throw ScenarioValidationException(
                "Scenario mismatch for scope '$scopeName': state $actual did not satisfy '$description'"
            )
        }
    }
}
