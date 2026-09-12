package ru.alexey.event.threads.test.scenario

import kotlinx.coroutines.CoroutineScope
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.scopeHolder

/**
 * A tiny, real (`thread<T>().then{}`/`.then(container){}`) cascade + state mutation, used by
 * [ScenarioDslTest] to demonstrate the scenario/dynamic test DSL ([ScenarioRecorder]/[scenario])
 * end-to-end: [ItemAdded] both cascades into a [TotalChanged] event (a cascading `.then { }`) and,
 * independently, updates a running-total [ru.alexey.event.threads.datacontainer.Datacontainer]
 * (a modification `.then(container) { }`). [ItemAdded]/[TotalChanged] stay `private` to this file -
 * [addItem] is the only way to trigger the cascade from outside it, the same encapsulation the
 * static-graph demo fixture ([ru.alexey.event.threads.test.graph.OrderPipelineFixture]) uses.
 *
 * Deliberately built with its `EventBus`'s (and its `Datacontainer`'s) dispatch pinned to
 * [busScope] rather than the library's own `Dispatchers.Default` default, so a test can drive it
 * deterministically with `kotlinx-coroutines-test`'s `advanceUntilIdle()`, and with [recorder]
 * (when given) wired in as a `watcher { }` at config time - see [ScenarioRecorder]'s KDoc for why
 * both of those matter.
 */
private data class ItemAdded(val amount: Int) : StrictEvent
private data class TotalChanged(val total: Int) : StrictEvent

/** Builds the (unloaded) `"Counter"` [ScopeHolder] described in this file's KDoc - call
 * `holder.findOrLoad("Counter")` before dispatching via [addItem], and `holder.free("Counter")`
 * once done, same as any other [ScopeHolder]. [recorder], when given, is wired to observe every
 * event `"Counter"`'s bus actually handles - see [recordInto]. */
fun counterHolder(busScope: CoroutineScope, recorder: ScenarioRecorder? = null): ScopeHolder = scopeHolder {
    scopeEmbedded("Counter") {
        config {
            createEventBus {
                coroutineScope { busScope }
                if (recorder != null) recordInto(recorder, "Counter")
            }
        }
        val total by datacontainer(flowResource(0)) { coroutineScope { busScope } }

        threads {
            thread<ItemAdded>()
                .then(total) { current, event -> current + event.amount }
                .then { event -> TotalChanged(event.amount) }

            thread<TotalChanged>().end { }
        }
    }
}

/** Dispatches an [ItemAdded] of [amount] onto this scope - the only trigger [ScenarioDslTest] has,
 * since [ItemAdded] is private to this fixture file. */
fun Scope.addItem(amount: Int) {
    this + ItemAdded(amount)
}
