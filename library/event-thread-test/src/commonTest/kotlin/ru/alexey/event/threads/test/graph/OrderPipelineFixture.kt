package ru.alexey.event.threads.test.graph

import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.scopeHolder

/**
 * A real, three-level-deep event cascade, built with the actual `thread<T>().then { }` DSL - not
 * a hand-built [EventGraph] - so [OrderPipelineCascadeDemoTest] exercises the whole path from a
 * live [ru.alexey.event.threads.scopeholder.ScopeHolder] through [toEventGraph] to the assertion
 * DSL, the same way a real app's test would.
 *
 * Shape (`"OrderPipeline"` is the only scope; depth counted from the root event):
 * ```
 * OrderPlaced                              (depth 0 - root)
 *   -> OrderValidated                      (depth 1)
 *        -> PaymentCharged                 (depth 2, branch A)
 *             -> OrderShipped              (depth 3, terminal - consume)
 *             -> ReceiptEmailQueued        (depth 3, deliberately orphaned - no thread<T>() at all)
 *        -> InventoryReserved              (depth 2, branch B, terminal - consume)
 * ```
 * `OrderValidated` and `PaymentCharged` each fan out via two chained `.then { }` calls on the same
 * `EventThread` - the library's normal way to make one event cascade into more than one - giving
 * this fixture both depth (3 hops) and branching (2-way at two different levels) in one shape, plus
 * one intentional [EventGraph.orphanEvents] leaf (`ReceiptEmailQueued`) to demonstrate that check
 * without needing a second scope.
 */
private data class OrderPlaced(val id: Int) : StrictEvent
private data class OrderValidated(val id: Int) : StrictEvent
private data class PaymentCharged(val id: Int) : StrictEvent
private data class InventoryReserved(val id: Int) : StrictEvent
private data class OrderShipped(val id: Int) : StrictEvent
private data class ReceiptEmailQueued(val id: Int) : StrictEvent

/** Builds the (unloaded) [ScopeHolder] described in this file's KDoc - call
 * `holder.findOrLoad("OrderPipeline")` before reading its metadata/graph, and `holder.free(...)`
 * once done, same as any other [ScopeHolder]. */
fun orderPipelineHolder(): ScopeHolder = scopeHolder {
    scopeEmbedded("OrderPipeline") {
        threads {
            thread<OrderPlaced>().then { event -> OrderValidated(event.id) }

            thread<OrderValidated>()
                .then { event -> PaymentCharged(event.id) }
                .then { event -> InventoryReserved(event.id) }

            thread<PaymentCharged>()
                .then { event -> OrderShipped(event.id) }
                .then { event -> ReceiptEmailQueued(event.id) }

            thread<InventoryReserved>().end { }
            thread<OrderShipped>().end { }
            // ReceiptEmailQueued gets no thread<T>() at all - the deliberate orphan.
        }
    }
}
