package ru.alexey.event.threads.test.scenario

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Demonstrates the scenario/dynamic test DSL ([ScenarioRecorder]/[scenario]/[runScenario]) against
 * [counterHolder]'s real cascade + state mutation, run under one shared `TestDispatcher` for
 * determinism (see [ScenarioRecorder]'s KDoc on why that pinning - and the watcher-based recording
 * itself - matters). This is the runtime counterpart to [ru.alexey.event.threads.test.graph.
 * OrderPipelineCascadeDemoTest]'s static checks - same "real ScopeHolder + real cascade" shape,
 * asserting what actually happened during one dispatch instead of what the graph says could
 * happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioDslTest {

    @Test
    fun expectEventsAndExpectStatePassAgainstARealDeterministicCascade() = runScenario(::counterHolder) { holder, _, recorder ->
        val counterScope = holder.findOrLoad("Counter")
        counterScope.addItem(5)
        advanceUntilIdle()

        scenario(recorder) {
            scope("Counter") {
                expectEvents("ItemAdded", "TotalChanged")
                expectState(counterScope.resolveOrThrow(), 5)
            }
        }
    }

    @Test
    fun expectEventsFailsWithTheFullTraceOnAMismatch() = runScenario(::counterHolder) { holder, _, recorder ->
        val counterScope = holder.findOrLoad("Counter")
        counterScope.addItem(5)
        advanceUntilIdle()

        val error = assertFailsWith<ScenarioValidationException> {
            scenario(recorder) {
                scope("Counter") { expectEvents("ItemAdded") }
            }
        }
        assertTrue("TotalChanged" in error.message.orEmpty())
    }

    @Test
    fun expectStateFailsWhenTheContainerDidNotReachTheExpectedValue() = runScenario(::counterHolder) { holder, _, recorder ->
        val counterScope = holder.findOrLoad("Counter")
        counterScope.addItem(5)
        advanceUntilIdle()

        assertFailsWith<ScenarioValidationException> {
            scenario(recorder) {
                scope("Counter") { expectState(counterScope.resolveOrThrow(), 999) }
            }
        }
    }

    @Test
    fun expectStatePredicateVariantDescribesTheFailure() = runScenario(::counterHolder) { holder, _, recorder ->
        val counterScope = holder.findOrLoad("Counter")
        counterScope.addItem(5)
        advanceUntilIdle()

        val error = assertFailsWith<ScenarioValidationException> {
            scenario(recorder) {
                scope("Counter") {
                    expectState(counterScope.resolveOrThrow<Int>(), "greater than 100") { it > 100 }
                }
            }
        }
        assertTrue("greater than 100" in error.message.orEmpty())
    }

    // Deliberately not using runScenario() here: it always wires a recorder into the holder it
    // builds, and this test specifically needs a holder built *without* one.
    @Test
    fun aScopeWithNoRecorderWiredInStaysEmpty() = runTest {
        val busScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val holder = counterHolder(busScope)
        try {
            val counterScope = holder.findOrLoad("Counter")
            counterScope.addItem(5)
            advanceUntilIdle()

            val recorder = ScenarioRecorder()
            scenario(recorder) {
                scope("Counter") { expectEvents() }
            }
        } finally {
            holder.free("Counter")
            busScope.cancel()
        }
    }
}
