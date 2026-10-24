package ru.alexey.event.threads.test.scenario

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.scopeholder.ScopeHolder

/**
 * Runs a scenario test: builds a fresh [ScenarioRecorder] and a `TestDispatcher`-backed
 * [CoroutineScope] shared for the whole scenario, has [holder] build the [ScopeHolder] under test
 * against them, runs [block], then closes the holder and cancels the bus scope - the boilerplate
 * every [ScenarioRecorder]-based test would otherwise repeat by hand. See [ScenarioRecorder]'s KDoc
 * for why sharing one `TestDispatcher` (rather than each scope defaulting to its own
 * `Dispatchers.Default`) is what makes [ScopeScenario.expectEvents]/[ScopeScenario.expectState]
 * deterministic under `advanceUntilIdle()` at all.
 *
 * ```
 * @Test
 * fun myScenario() = runScenario(::counterHolder) { holder, _, recorder ->
 *     val scope = holder.findOrLoad("Counter")
 *     scope.addItem(5)
 *     advanceUntilIdle()
 *     scenario(recorder) { scope("Counter") { expectEvents("ItemAdded", "TotalChanged") } }
 * }
 * ```
 *
 * [holder] is called with the same [CoroutineScope]/[ScenarioRecorder] [block] receives -
 * typically a scope factory like `counterHolder(busScope, recorder)` that wires every scope's own
 * `config { createEventBus { coroutineScope { busScope }; recordInto(recorder, "Name") } } }`.
 * Cleanup calls [ScopeHolder.close] (closing every scope the test loaded, whatever their names),
 * not a specific `free(key)` - a generic runner can't know the holder's scope keys up front.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun runScenario(
    holder: (busScope: CoroutineScope, recorder: ScenarioRecorder) -> ScopeHolder,
    block: suspend TestScope.(holder: ScopeHolder, busScope: CoroutineScope, recorder: ScenarioRecorder) -> Unit
): TestResult = runTest {
    val busScope = CoroutineScope(StandardTestDispatcher(testScheduler))
    val recorder = ScenarioRecorder()
    val scopeHolder = holder(busScope, recorder)
    try {
        block(scopeHolder, busScope, recorder)
    } finally {
        scopeHolder.close()
        busScope.cancel()
    }
}
