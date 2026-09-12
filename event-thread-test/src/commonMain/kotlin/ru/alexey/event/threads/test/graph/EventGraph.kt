package ru.alexey.event.threads.test.graph

/**
 * An event, qualified by the scope whose `thread<T>()` handles it. [EventGraph] node identity is
 * always scope-qualified, never a bare event class name: `.then { }`'s cascade step dispatches
 * onto the *same* scope's own [ru.alexey.event.threads.bus.EventBus] only (`eventBus` inside
 * [ru.alexey.event.threads.Scope.then] resolves to the enclosing `Scope`'s own bus), so two
 * different scopes handling an event of the same class name are two unrelated nodes here, never
 * merged into one - merging them would silently hide a real orphan in one scope behind an
 * unrelated handler in another.
 */
data class ScopedEvent(val scope: String, val event: String) {
    override fun toString(): String = "$scope::$event"
}

/** One `cascade` step, by simple event-class name: handling [from] on [scope] may dispatch [to] -
 * see [ru.alexey.event.threads.EventThreadActionInfo.producedType]. Both ends always belong to the
 * same [scope] (cascade can't cross scopes - see [ScopedEvent]). Static, not a runtime trace: [to]
 * is the type Kotlin inferred for the `.then { }` factory's `reified` return type at its call
 * site, so a branching factory shows up as its common supertype here, not each leaf it can
 * actually dispatch at runtime. */
data class EventEdge(
    val from: String,
    val to: String,
    val scope: String,
)

/**
 * A static event-flow graph built from one or more scopes' metadata (see [toEventGraph]) - what a
 * dispatched event *could* trigger transitively, not a record of what actually happened in any
 * one run (for that, capture a `watcher`'s dispatch trace instead).
 *
 * [handledEvents] are every event with at least one `thread<T>()` registration, one per owning
 * scope; [edges] are every `cascade` step found. [nodes] is their union, since a produced event
 * with no handler in its own scope ([orphanEvents]) is still a node worth reporting, and a handled
 * event that produces nothing is a legitimate terminal ([ru.alexey.event.threads.EventType.
 * consume]/`modification`).
 */
class EventGraph(
    val handledEvents: Set<ScopedEvent>,
    val edges: List<EventEdge>,
) {
    val producedEvents: Set<ScopedEvent> get() = edges.mapTo(mutableSetOf()) { ScopedEvent(it.scope, it.to) }
    val nodes: Set<ScopedEvent> get() = handledEvents + producedEvents

    private val adjacency: Map<ScopedEvent, List<ScopedEvent>> by lazy {
        edges.groupBy({ ScopedEvent(it.scope, it.from) }, { ScopedEvent(it.scope, it.to) })
    }

    /** Events a `cascade` step dispatches that the *same* scope has no `thread<T>()` for - i.e.
     * the event is produced but structurally unreachable by any handler (a handler on a different
     * scope for an event of the same class name does not count - see [ScopedEvent]). */
    fun orphanEvents(): Set<ScopedEvent> = producedEvents - handledEvents

    /** Every event transitively reachable from dispatching [event] on [scope], following `cascade`
     * edges only ([event] itself is not included unless a cycle reaches back to it). Every result
     * shares [scope], since cascade never leaves the scope it started in. */
    fun reachableFrom(scope: String, event: String): Set<ScopedEvent> {
        val visited = mutableSetOf<ScopedEvent>()
        val queue = ArrayDeque(listOf(ScopedEvent(scope, event)))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in adjacency[current].orEmpty()) {
                if (visited.add(next)) queue.addLast(next)
            }
        }
        return visited
    }

    /**
     * Structural cycles in the produces-graph (Tarjan's strongly-connected-components), each
     * returned as its member events. A cycle here means "event flow *can* loop back on itself
     * within one scope", not "this is a bug" - a convergent retry/backoff loop is a legitimate
     * cycle, so treat this as advisory input to a human (or a test author's) decision, not an
     * automatic failure.
     */
    fun cycles(): List<List<ScopedEvent>> {
        var index = 0
        val indices = mutableMapOf<ScopedEvent, Int>()
        val lowlink = mutableMapOf<ScopedEvent, Int>()
        val onStack = mutableSetOf<ScopedEvent>()
        val stack = ArrayDeque<ScopedEvent>()
        val result = mutableListOf<List<ScopedEvent>>()

        fun strongConnect(root: ScopedEvent) {
            // Iterative Tarjan: an explicit work-stack of (node, next-neighbour-index-to-visit)
            // frames instead of recursion, so this doesn't blow the call stack on a deep/large
            // event graph.
            val work = ArrayDeque<Pair<ScopedEvent, Int>>()
            work.addLast(root to 0)
            indices[root] = index
            lowlink[root] = index
            index++
            stack.addLast(root)
            onStack += root

            while (work.isNotEmpty()) {
                val (v, i) = work.removeLast()
                val neighbours = adjacency[v].orEmpty()

                if (i < neighbours.size) {
                    work.addLast(v to i + 1)
                    val w = neighbours[i]
                    when {
                        w !in indices -> {
                            indices[w] = index
                            lowlink[w] = index
                            index++
                            stack.addLast(w)
                            onStack += w
                            work.addLast(w to 0)
                        }
                        w in onStack -> lowlink[v] = minOf(lowlink.getValue(v), indices.getValue(w))
                    }
                } else {
                    if (lowlink.getValue(v) == indices.getValue(v)) {
                        val component = mutableListOf<ScopedEvent>()
                        while (true) {
                            val w = stack.removeLast()
                            onStack -= w
                            component += w
                            if (w == v) break
                        }
                        val selfLoop = component.size == 1 && adjacency[component[0]]?.contains(component[0]) == true
                        if (component.size > 1 || selfLoop) result += component
                    }
                    val parent = work.lastOrNull()?.first
                    if (parent != null) lowlink[parent] = minOf(lowlink.getValue(parent), lowlink.getValue(v))
                }
            }
        }

        for (node in nodes) {
            if (node !in indices) strongConnect(node)
        }
        return result
    }

    /** Human-readable dump of every node/edge plus detected [orphanEvents]/[cycles] - meant for a
     * failed test's message or a plain debug printout, not for machine parsing. */
    fun render(): String = buildString {
        appendLine("Event graph: ${nodes.size} event(s), ${edges.size} cascade edge(s)")
        appendLine("Handled: ${handledEvents.map { it.toString() }.sorted()}")

        val orphans = orphanEvents()
        if (orphans.isNotEmpty()) {
            appendLine("Orphan (produced, no listener in the same scope): ${orphans.map { it.toString() }.sorted()}")
        }

        val found = cycles()
        if (found.isNotEmpty()) {
            appendLine("Cycles: ${found.joinToString { it.joinToString(" -> ") }}")
        }

        appendLine("Edges:")
        edges.sortedWith(compareBy({ it.scope }, { it.from }, { it.to })).forEach {
            appendLine("  [${it.scope}] ${it.from} -> ${it.to}")
        }
    }

    /**
     * Human-readable dump of just [event]'s cascade *tree* on [scope] - every step it can trigger,
     * transitively, indented by depth - instead of [render]'s whole-graph listing. Meant for
     * eyeballing one flow in isolation (a failing assertion's message, or a plain debug printout
     * while writing a `graph("$scope") { }` block), not machine parsing.
     *
     * A node reachable more than once (a cycle, or two different steps both leading to it) is
     * expanded in full on its first appearance only; every later occurrence prints as `(see
     * above)` instead of re-expanding, so this always terminates even on a cyclic graph. An orphan
     * leaf (produced, no handler) is marked inline rather than silently rendered as if it were a
     * normal terminal.
     */
    fun renderCascade(scope: String, event: String): String = buildString {
        appendLine("Cascade from '$scope::$event':")
        val orphans = orphanEvents()
        val seen = mutableSetOf<ScopedEvent>()

        fun walk(node: ScopedEvent, depth: Int) {
            val indent = "  ".repeat(depth)
            val arrow = if (depth == 0) "" else "-> "
            if (!seen.add(node)) {
                appendLine("$indent$arrow${node.event} (see above)")
                return
            }
            val suffix = if (node in orphans) " [orphan - no handler]" else ""
            appendLine("$indent$arrow${node.event}$suffix")
            adjacency[node].orEmpty().sortedBy { it.event }.forEach { child -> walk(child, depth + 1) }
        }

        walk(ScopedEvent(scope, event), 0)
    }
}
