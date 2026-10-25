package org.company.sample.typed

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.company.sample.AddTodo
import org.company.sample.Todo
import org.company.sample.TodoDraft
import org.company.sample.TodoListBase
import org.company.sample.VisibleTodos
import org.company.sample.containers.DraftContainer
import org.company.sample.containers.TodosContainer
import org.company.sample.containers.TotalAddedContainer
import org.company.sample.containers.VisibleTodosContainer
import org.company.sample.events.AddTodoStateProcessor
import org.company.sample.events.LifecycleEventsSideEffect
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.alexey.event.threads.LifecycleEvents
import ru.alexey.event.threads.bus.EventBus
import ru.alexey.event.threads.bus.EventBusBuilder
import ru.alexey.event.threads.cache.cacheJsonResource
import ru.alexey.event.threads.resources.flowResource
import kotlin.random.Random

/*
 * The developer's half of the example app's typed scopes - generated from
 * src/commonMain/resources/event-thread/scopes.json into build/generated/eventThread (scopes in
 * `.scopes`, handler bases in `.events`, containers in `.containers`). Each piece mirrors the DSL
 * version in TodoApp.kt; the scope's DependencyProvider (Koin, below) supplies them. Handlers with
 * a `rule` in the schema (toggle, delete, rename, the add limit, ...) need nothing here: the
 * generated `...Rule` class is their binding's default. Containers with a schema `initial`
 * (showCompleted, limitHits, selectedList) likewise default to `<State>ContainerInitial`.
 * Binding one below would override either.
 * `factory`, not `single`: every scope instance needs its own containers, handlers and bus.
 */

/** TodoDomain reports AddLimitReached from this many adds on - same as the DSL app, and the `5` in
 * the schema's AddTodo rule. */
const val ADD_LIMIT = 5

val typedTodoModule = module {
    single<Json> { Json }

    // --- buses: what the DSL scopes set via `config { createEventBus { } }` -----------------
    // Bound under the scope's key; ScopeTyped picks it up, else uses a default bus.
    for (list in listOf("Work", "Personal")) {
        factory<EventBus>(named(list)) {
            EventBusBuilder().apply { watcher { Logger.d(tag = "TODO") { it.toString() } } }.build()
        }
    }
    factory<EventBus>(named("TodoDraft")) {
        EventBusBuilder().apply { coroutineScope { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) } }.build()
    }

    // --- TodoDomain ---------------------------------------------------------------------------
    factory<TotalAddedContainer> { TotalAddedImpl() }

    // --- Work / Personal (one set of bases for both - same spec) ------------------------------
    factory<TodosContainer> { (params: TodoListBase.Params) -> TodosImpl(params, get()) }
    factory<VisibleTodosContainer> { (deps: VisibleTodosContainer.Deps) -> VisibleTodosImpl(deps) }
    factory<AddTodoStateProcessor> { (deps: AddTodoStateProcessor.Deps) -> AddTodoImpl(deps) }
    factory<LifecycleEventsSideEffect> { LogLifecycleImpl() }

    // --- TodoTabs: nothing - selectedList has an `initial`, SelectList a rule ------------------

    // --- Suggestions: just the HttpClient - its API (Ktor), request and containers are generated
    single<HttpClient> { fakeIdeasClient() }

    // --- TodoDraft ----------------------------------------------------------------------------
    factory<DraftContainer> { (params: TodoDraft.Params) -> DraftImpl(params) }
}

// === TodoDomain ================================================================================

private class TotalAddedImpl : TotalAddedContainer(CoroutineScope(Dispatchers.Main + SupervisorJob())) {
    override val source = flowResource(0)
    // As the DSL container: pipeline on Main, every value logged.
    override fun onEach(value: Int) = Logger.d(tag = "TodoDomainState") { value.toString() }
}

// === Work / Personal ===========================================================================

/** Reads from memory; the JSON file is read once to hydrate, then only written - per list
 * ([TodoListBase.Params.cacheKey]). */
private class TodosImpl(params: TodoListBase.Params, json: Json) : TodosContainer() {
    override val source = cacheJsonResource(key = params.cacheKey, initial = emptyList<Todo>(), json = json)
}

/** Composite state: the raw list, filtered by the show-completed flag - nobody writes it. */
private class VisibleTodosImpl(deps: Deps) : VisibleTodosContainer(deps) {
    override val source = flowResource(VisibleTodos(emptyList()))
    override val transforms = listOf(
        transform(todos) { list, _ -> VisibleTodos(list) },
        transform(showCompleted) { show, current -> if (show) current else VisibleTodos(current.items.filterNot { it.done }) },
    )
}

private class AddTodoImpl(deps: Deps) : AddTodoStateProcessor(deps) {
    override suspend fun process(event: AddTodo, state: List<Todo>) = state + Todo(id = Random.nextLong(), text = event.text)
}

private class LogLifecycleImpl : LifecycleEventsSideEffect() {
    override suspend fun handle(event: LifecycleEvents) = Logger.d(tag = "LifecycleEvents") { event.toString() }
}

// === TodoDraft =================================================================================

/** On Main.immediate (as the DSL one): the text field's value must update in the same frame. */
private class DraftImpl(params: TodoDraft.Params) :
    DraftContainer(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    override val source = flowResource(params.initialText)
}
