package org.company.sample.typed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.company.sample.AddTodo
import org.company.sample.SetShowCompleted
import org.company.sample.Todo
import org.company.sample.TodoListBase
import org.company.sample.ToggleTodo
import org.company.sample.VisibleTodos
import org.company.sample.scopes.PersonalScopeKey
import org.company.sample.scopes.TodoTabsScopeKey
import org.company.sample.scopes.WorkScope
import org.company.sample.scopes.WorkScopeKey
import ru.alexey.event.threads.cache.CacheDirectoryProvider
import ru.alexey.event.threads.scopeholder.typed.load
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Work/Personal of the typed app holder - Android-only for the same reason as
 * TodoWorkPersonalGraphTest: their todos container is a cacheJsonResource (real file I/O). */
@OptIn(ExperimentalCoroutinesApi::class)
class TypedWorkPersonalTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempDir = Files.createTempDirectory("typed-todo-cache").toFile()
        CacheDirectoryProvider { tempDir.path }
    }

    @AfterTest
    fun tearDown() {
        CacheDirectoryProvider.reset()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5000) { block() } }

    private val workParams = TodoListBase.Params(cacheKey = "todos_work")

    @Test
    fun listHandlersAndTheDerivedVisibleView() = runTest {
        val holder = provideTypedTodoScopeHolder()
        val work = holder.load(WorkScopeKey, workParams)!!
        assertTrue(work is WorkScope)
        val todos = work.resolveOrThrow<List<Todo>>()
        val visible = work.resolveOrThrow<VisibleTodos>()

        work + AddTodo("report")
        awaitReal { todos.first { it.size == 1 } }
        work + AddTodo("call")
        val added = awaitReal { todos.first { it.size == 2 } }
        work + ToggleTodo(added.first { it.text == "report" }.id)
        awaitReal { todos.first { list -> list.any { it.done } } }

        work + SetShowCompleted(false)
        assertEquals(listOf("call"), awaitReal { visible.first { it.items.size == 1 } }.items.map { it.text })
        holder.close()
    }

    @Test
    fun workAndPersonalKeepSeparateLists() = runTest {
        val holder = provideTypedTodoScopeHolder()
        val work = holder.load(WorkScopeKey, workParams)!!
        val personal = holder.load(PersonalScopeKey, TodoListBase.Params(cacheKey = "todos_personal"))!!

        work + AddTodo("report")
        awaitReal { work.resolveOrThrow<List<Todo>>().first { it.isNotEmpty() } }

        assertTrue(personal.resolveOrThrow<List<Todo>>().value.isEmpty())
        assertTrue(File(tempDir, "todos_work.json").exists() || tempDir.walk().any { it.name.contains("todos_work") })
        holder.close()
    }

    @Test
    fun domainLimitReachesTheActiveListThroughConsume() = runTest {
        val holder = provideTypedTodoScopeHolder()
        holder.load(TodoTabsScopeKey) // brings TodoDomain (dependsOn)
        val work = holder.load(WorkScopeKey, workParams)!!
        val todos = work.resolveOrThrow<List<Todo>>()

        repeat(ADD_LIMIT) { i ->
            holder + AddTodo("item $i") // like the UI: broadcast to every active scope
            awaitReal { todos.first { it.size == i + 1 } }
        }

        assertEquals(1, awaitReal { work.resolveOrThrow<Int>().first { it == 1 } })
        holder.close()
    }

    /** A widget's untyped `scope("Work")` reaches the list the tab loaded with typed params. */
    @Test
    fun widgetLookupReachesTheTypedList() {
        val holder = provideTypedTodoScopeHolder()
        val work = holder.load(WorkScopeKey, workParams)

        assertSame(work, holder.findOrLoad("Work") { emptyMap() })
        holder.close()
    }
}
