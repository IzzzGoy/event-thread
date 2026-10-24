package org.company.sample.typed

import kotlinx.coroutines.test.runTest
import org.company.sample.AddTodo
import org.company.sample.SetShowCompleted
import org.company.sample.TodoListBase
import org.company.sample.ToggleTodo
import org.company.sample.scopes.PersonalScopeContract
import org.company.sample.scopes.PersonalScopeDependencyBindings
import org.company.sample.scopes.WorkScopeContract
import org.company.sample.scopes.WorkScopeDependencyBindings
import org.company.sample.scopes.WorkScopeProbe
import org.koin.dsl.koinApplication
import ru.alexey.event.threads.cache.CacheDirectoryProvider
import ru.alexey.event.threads.di.koin.asDependencyProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Work/Personal's todos are cached on disk - point the cache at a temp dir per test. */
private class TempCache {
    lateinit var dir: File
    fun install() {
        dir = Files.createTempDirectory("typed-contract-cache").toFile()
        CacheDirectoryProvider { dir.path }
    }
    fun remove() {
        CacheDirectoryProvider.reset()
        dir.deleteRecursively()
    }
}

private fun appProvider() = koinApplication { modules(typedTodoModule) }.koin.asDependencyProvider()

class WorkContractTest : WorkScopeContract() {
    private val cache = TempCache()
    @BeforeTest fun cache() = cache.install()
    @AfterTest fun uncache() = cache.remove()

    override fun bindings() = WorkScopeDependencyBindings(appProvider())
    override fun params() = TodoListBase.Params(cacheKey = "contract_work")
}

class PersonalContractTest : PersonalScopeContract() {
    private val cache = TempCache()
    @BeforeTest fun cache() = cache.install()
    @AfterTest fun uncache() = cache.remove()

    override fun bindings() = PersonalScopeDependencyBindings(appProvider())
    override fun params() = TodoListBase.Params(cacheKey = "contract_personal")
}

/** A behaviour test on the generated probe: typed containers, real implementations. */
class WorkScopeProbeTest {
    private val cache = TempCache()
    @BeforeTest fun cache() = cache.install()
    @AfterTest fun uncache() = cache.remove()

    @Test
    fun hidingDoneTodosFiltersTheVisibleList() = runTest {
        val probe = WorkScopeProbe(TodoListBase.Params("probe_work"), WorkScopeDependencyBindings(appProvider()))

        probe.send(AddTodo("milk"))
        probe.send(AddTodo("bread"))
        val todos = probe.await(probe.todos) { it.size == 2 }
        probe.send(ToggleTodo(todos.first { it.text == "milk" }.id))
        probe.await(probe.todos) { list -> list.any { it.done } }
        probe.send(SetShowCompleted(false))

        assertEquals(listOf("bread"), probe.await(probe.visibleTodos) { it.items.size == 1 }.items.map { it.text })
        probe.close()
    }
}
