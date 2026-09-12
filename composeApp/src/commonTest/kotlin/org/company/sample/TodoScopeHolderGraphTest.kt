package org.company.sample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.generateActiveSchema
import ru.alexey.event.threads.test.graph.assertNoCycles
import ru.alexey.event.threads.test.graph.invoke
import ru.alexey.event.threads.test.graph.toEventGraph
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Static-validation coverage of [provideTodoScopeHolder]'s real event-flow graph, using
 * `event-thread-test`'s [ru.alexey.event.threads.test.graph.EventGraph].
 *
 * Only "TodoDomain"/"TodoTabs"/"TodoDraft" are loaded here, not "Work"/"Personal" ("TodoListBase"'s
 * implementers): both build a `cacheJsonResource` container, which eagerly does real file I/O
 * behind `ContextProvider.provider()` - a real `android.content.Context`, unavailable to plain
 * `commonTest` code. That seam (`ru.alexey.event.threads.cache.CacheDirectoryProvider`, routing
 * the I/O to a temp directory instead) is itself Android-only, so it can only be used from an
 * Android-specific test source set - see `TodoWorkPersonalGraphTest` in `androidUnitTest` for that
 * coverage instead of here.
 */
class TodoScopeHolderGraphTest {

    @BeforeTest
    fun setUp() {
        // TodoDomain's and TodoDraft's containers pin their bus/container coroutineScope to
        // Dispatchers.Main(.immediate) - unavailable by default outside a real Android/iOS main
        // loop, so building either scope without this override throws before a single thread<T>()
        // even registers.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loadedHolder(vararg keys: String): ScopeHolder {
        val holder = provideTodoScopeHolder()
        keys.forEach { holder.findOrLoad(it) }
        return holder
    }

    @Test
    fun noStructuralCyclesAmongTheLoadableScopes() {
        val holder = loadedHolder("TodoDomain", "TodoTabs", "TodoDraft")
        try {
            holder.generateActiveSchema().toEventGraph().assertNoCycles()
        } finally {
            holder.free("TodoDomain")
            holder.free("TodoTabs")
            holder.free("TodoDraft")
        }
    }

    @Test
    fun everyDeclaredThreadShowsUpInTheStaticGraph() {
        val holder = loadedHolder("TodoDomain", "TodoTabs", "TodoDraft")
        try {
            val graph = holder.generateActiveSchema().toEventGraph()

            graph("TodoDomain") { reaches("AddTodo") }
            graph("TodoTabs") { reaches("SelectList") }
            graph("TodoDraft") { reaches("SetDraftText") }

        } finally {
            holder.free("TodoDomain")
            holder.free("TodoTabs")
            holder.free("TodoDraft")
        }
    }

    @Test
    fun addLimitReachedIsInvisibleToTheStaticGraph() {
        val holder = loadedHolder("TodoDomain")
        try {
            val graph = holder.generateActiveSchema().toEventGraph()

            graph("TodoDomain") {
                // TodoDomain's `.end { if (...) eventBus += AddLimitReached(...) }` is the app's
                // only real event-to-event flow (domain verdict -> UI), but it's dispatched
                // *imperatively* inside a `consume` step, not returned from a cascading
                // `.then { }` - so the metadata capture this graph is built from
                // (EventThreadActionInfo.producedType, only filled by the cascading `.then`
                // overload) never sees it. Pinned as a passing assertion, not a TODO comment, so
                // it can't silently go stale if that capture is ever extended to imperative `+=`.
                doesNotCascade("AddTodo", "AddLimitReached")
            }
        } finally {
            holder.free("TodoDomain")
        }
    }
}
