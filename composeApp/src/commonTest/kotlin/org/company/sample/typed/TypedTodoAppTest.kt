package org.company.sample.typed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.company.sample.AddLimitReached
import org.company.sample.AddTodo
import org.company.sample.SelectList
import org.company.sample.SetDraftText
import org.company.sample.TodoDraft
import org.company.sample.containers.DraftContainer
import org.company.sample.events.SetDraftTextStateProcessor
import org.company.sample.scopes.TodoDomainScope
import org.company.sample.scopes.TodoDraftScopeBindings
import org.company.sample.scopes.registerTodoDraftScope
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeholder.scopeHolder
import org.company.sample.scopes.TodoDomainScopeKey
import org.company.sample.scopes.TodoDraftScopeKey
import org.company.sample.scopes.TodoTabsScope
import org.company.sample.scopes.TodoTabsScopeKey
import ru.alexey.event.threads.scopeholder.typed.find
import ru.alexey.event.threads.scopeholder.typed.load
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The typed app holder ([provideTypedTodoScopeHolder]) - the scopes without file I/O; Work/Personal
 * (cacheJsonResource) are covered in androidUnitTest's TypedWorkPersonalTest, as for the DSL app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypedTodoAppTest {

    @BeforeTest
    fun setUp() {
        // TodoDomain's container and TodoDraft's bus/container run on Dispatchers.Main(.immediate).
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5000) { block() } }

    @Test
    fun todoTabsLoadsTodoDomainThroughDependsOn() {
        val holder = provideTypedTodoScopeHolder()

        assertTrue(holder.load(TodoTabsScopeKey) is TodoTabsScope)

        assertTrue(holder.find(TodoDomainScopeKey) is TodoDomainScope)
        holder.close()
    }

    @Test
    fun selectingAListUpdatesTheTabsState() = runTest {
        val holder = provideTypedTodoScopeHolder()
        val tabs = holder.load(TodoTabsScopeKey)!!
        val selected = tabs.resolveOrThrow<String>()
        assertEquals("Work", selected.value)

        holder + SelectList("Personal")

        assertEquals("Personal", awaitReal { selected.first { it == "Personal" } })
        holder.close()
    }

    @Test
    fun eachDraftParamsIsItsOwnDraftScope() = runTest {
        val holder = provideTypedTodoScopeHolder()
        val newTodo = holder.load(TodoDraftScopeKey, TodoDraft.Params())!!
        val editing = holder.load(TodoDraftScopeKey, TodoDraft.Params(initialText = "milk"))!!

        assertNotSame(newTodo, editing)
        assertEquals("", newTodo.resolveOrThrow<String>().value)
        assertEquals("milk", editing.resolveOrThrow<String>().value)

        editing + SetDraftText("milk 2%")
        assertEquals("milk 2%", awaitReal { editing.resolveOrThrow<String>().first { it == "milk 2%" } })
        holder.close()
    }

    /** The compile-checked path: the scope built from a hand-written TodoDraftScopeBindings - no
     * DependencyProvider. Leaving out any member of the interface is a compile error. */
    @Test
    fun draftScopeFromHandWrittenBindings() = runTest {
        class Draft(params: TodoDraft.Params) : DraftContainer() {
            override val source = flowResource(params.initialText)
        }
        class Set(deps: SetDraftTextStateProcessor.Deps) : SetDraftTextStateProcessor(deps) {
            override suspend fun process(event: SetDraftText, state: String) = event.text.uppercase()
        }
        val bindings = object : TodoDraftScopeBindings {
            override fun draft(params: TodoDraft.Params) = Draft(params)
            override fun setDraftTextStateProcessor(deps: SetDraftTextStateProcessor.Deps, params: TodoDraft.Params) = Set(deps)
        }
        val holder = scopeHolder { registerTodoDraftScope { _, _ -> bindings } }

        val draft = holder.load(TodoDraftScopeKey, TodoDraft.Params("hi"))!!
        assertEquals("hi", draft.resolveOrThrow<String>().value)
        draft + SetDraftText("typed")

        assertEquals("TYPED", awaitReal { draft.resolveOrThrow<String>().first { it == "TYPED" } })
        holder.close()
    }

    @Test
    fun domainReportsTheLimitOnExactlyTheLimitingAdd() = runTest {
        val holder = provideTypedTodoScopeHolder()
        holder.load(TodoTabsScopeKey)
        val domain = holder.find(TodoDomainScopeKey)!!
        val total = domain.resolveOrThrow<Int>()

        repeat(ADD_LIMIT - 1) { i ->
            domain + AddTodo("item $i")
            awaitReal { total.first { it == i + 1 } }
        }
        // The bus output replays its last event, so this sees AddLimitReached even if it subscribes
        // a beat after the dispatch below.
        val reported = async(Dispatchers.Default) {
            withTimeout(5000) { domain.eventBus.output.first { it is AddLimitReached } }
        }
        domain + AddTodo("the limiting one")

        // Read-your-writes: the check after the tally sees this add, not the previous count.
        assertEquals(AddLimitReached(ADD_LIMIT), reported.await())
        holder.close()
    }
}
