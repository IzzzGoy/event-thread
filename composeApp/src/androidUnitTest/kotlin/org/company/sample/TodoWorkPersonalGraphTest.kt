package org.company.sample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.alexey.event.threads.cache.CacheDirectoryProvider
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.generateActiveSchema
import ru.alexey.event.threads.scopeholder.typed.typedParameters
import ru.alexey.event.threads.test.graph.assertNoCycles
import ru.alexey.event.threads.test.graph.invoke
import ru.alexey.event.threads.test.graph.toEventGraph
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Covers "Work"/"Personal" (via "TodoListBase"), which [TodoScopeHolderGraphTest] (in
 * `commonTest`, so it can't reference this Android-only seam) skips: both build a
 * `cacheJsonResource` container that does real file I/O behind `ContextProvider`/`Context` -
 * [CacheDirectoryProvider] routes that to a temp directory for the duration of each test instead,
 * so this needs neither Robolectric nor a real device/emulator. Android-only (this module's
 * `androidUnitTest` source set) because that seam is itself Android-only, same as `ContextProvider`.
 */
class TodoWorkPersonalGraphTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        // TodoDraft/TodoDomain aren't loaded in this test file, but TodoListBase's own
        // `createEventBus { watcher { } }` config doesn't set a coroutineScope, so it defaults to
        // Dispatchers.Default - Main isn't actually needed here. Set anyway: cheap, and keeps this
        // file resilient if a future edit to TodoListBase/Work/Personal starts needing it too.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempDir = Files.createTempDirectory("event-thread-cache-test").toFile()
        CacheDirectoryProvider { tempDir.path }
    }

    @AfterTest
    fun tearDown() {
        CacheDirectoryProvider.reset()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun loadedHolder(vararg keys: String): ScopeHolder {
        val holder = provideTodoScopeHolder()
        keys.forEach { key ->
            // Work/Personal/TodoDraft now declare typed `TodoListBase.Params`/`TodoDraft.Params`
            // instead of an untyped-Parameters `resolveOrDefault(...)` - a raw-string findOrLoad
            // with none no longer has an implicit default to fall back on, so it must be supplied
            // explicitly here, same as any real caller (see TodoTabsScreen/EditTodoScreen in
            // TodoApp.kt).
            when (key) {
                "Work" -> holder.findOrLoad(key) { typedParameters(TodoListBase.Params(cacheKey = "todos_work")) }
                "Personal" -> holder.findOrLoad(key) { typedParameters(TodoListBase.Params(cacheKey = "todos_personal")) }
                "TodoDraft" -> holder.findOrLoad(key) { typedParameters(TodoDraft.Params()) }
                else -> holder.findOrLoad(key)
            }
        }
        return holder
    }

    @Test
    fun workAndPersonalRegisterEveryThreadInheritedFromTodoListBase() {
        val holder = loadedHolder("Work", "Personal")
        try {
            val graph = holder.generateActiveSchema().toEventGraph()

            val expectedEvents = listOf(
                "AddTodo", "ToggleTodo", "DeleteTodo", "SaveTodoText",
                "SetShowCompleted", "AddLimitReached", "LifecycleEvents",
            )
            for (scope in listOf("Work", "Personal")) {
                graph(scope) {
                    expectedEvents.forEach { reaches(it) }
                }
            }
        } finally {
            holder.free("Work")
            holder.free("Personal")
        }
    }

    @Test
    fun fullAppGraphHasNoStructuralCycles() {
        val holder = loadedHolder("TodoDomain", "Work", "Personal", "TodoTabs", "TodoDraft")
        try {
            holder.generateActiveSchema().toEventGraph().assertNoCycles()
        } finally {
            holder.free("TodoDomain")
            holder.free("Work")
            holder.free("Personal")
            holder.free("TodoTabs")
            holder.free("TodoDraft")
        }
    }
}
