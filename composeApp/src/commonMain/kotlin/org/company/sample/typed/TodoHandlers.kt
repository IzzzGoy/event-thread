package org.company.sample.typed

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.company.sample.AddLimitReached
import org.company.sample.AddTodo
import org.company.sample.DeleteTodo
import org.company.sample.SaveTodoText
import org.company.sample.SelectList
import org.company.sample.SetDraftText
import org.company.sample.SetShowCompleted
import org.company.sample.Todo
import org.company.sample.TodoDraft
import org.company.sample.TodoListBase
import org.company.sample.ToggleTodo
import org.company.sample.VisibleTodos
import org.company.sample.containers.DraftContainer
import org.company.sample.containers.LimitHitsContainer
import org.company.sample.containers.SelectedListContainer
import org.company.sample.containers.ShowCompletedContainer
import org.company.sample.containers.TodosContainer
import org.company.sample.containers.TotalAddedContainer
import org.company.sample.containers.VisibleTodosContainer
import org.company.sample.events.AddLimitReachedStateProcessor
import org.company.sample.events.AddTodoEventCascader
import org.company.sample.events.AddTodoStateProcessor
import org.company.sample.events.CountAddedTodos
import org.company.sample.events.DeleteTodoStateProcessor
import org.company.sample.events.LifecycleEventsSideEffect
import org.company.sample.events.SaveTodoTextStateProcessor
import org.company.sample.events.SelectListStateProcessor
import org.company.sample.events.SetDraftTextStateProcessor
import org.company.sample.events.SetShowCompletedStateProcessor
import org.company.sample.events.ToggleTodoStateProcessor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.alexey.event.threads.LifecycleEvents
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.EventBus
import ru.alexey.event.threads.bus.EventBusBuilder
import ru.alexey.event.threads.cache.cacheJsonResource
import ru.alexey.event.threads.resources.flowResource
import kotlin.random.Random

/*
 * The developer's half of the example app's typed scopes - generated from
 * src/commonMain/resources/event-thread/scopes.json into build/generated/eventThread (scopes in
 * `.scopes`, handler bases in `.events`, containers in `.containers`). Each piece mirrors the DSL
 * version in TodoApp.kt; the scope's DependencyProvider (Koin, below) supplies all of them.
 * `factory`, not `single`: every scope instance needs its own containers, handlers and bus.
 */

/** TodoDomain reports [AddLimitReached] from this many adds on - same as the DSL app. */
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
    factory<CountAddedTodos> { (deps: CountAddedTodos.Deps) -> CountAddedTodosImpl(deps) }
    factory<AddTodoEventCascader> { (deps: AddTodoEventCascader.Deps) -> AddLimitCheck(deps) }

    // --- Work / Personal (one set of bases for both - same spec) ------------------------------
    factory<TodosContainer> { (params: TodoListBase.Params) -> TodosImpl(params, get()) }
    factory<ShowCompletedContainer> { ShowCompletedImpl() }
    factory<LimitHitsContainer> { LimitHitsImpl() }
    factory<VisibleTodosContainer> { (deps: VisibleTodosContainer.Deps) -> VisibleTodosImpl(deps) }
    factory<AddTodoStateProcessor> { (deps: AddTodoStateProcessor.Deps) -> AddTodoImpl(deps) }
    factory<ToggleTodoStateProcessor> { (deps: ToggleTodoStateProcessor.Deps) -> ToggleTodoImpl(deps) }
    factory<DeleteTodoStateProcessor> { (deps: DeleteTodoStateProcessor.Deps) -> DeleteTodoImpl(deps) }
    factory<SaveTodoTextStateProcessor> { (deps: SaveTodoTextStateProcessor.Deps) -> SaveTodoTextImpl(deps) }
    factory<SetShowCompletedStateProcessor> { (deps: SetShowCompletedStateProcessor.Deps) -> SetShowCompletedImpl(deps) }
    factory<AddLimitReachedStateProcessor> { (deps: AddLimitReachedStateProcessor.Deps) -> CountLimitHitsImpl(deps) }
    factory<LifecycleEventsSideEffect> { LogLifecycleImpl() }

    // --- TodoTabs -----------------------------------------------------------------------------
    factory<SelectedListContainer> { SelectedListImpl() }
    factory<SelectListStateProcessor> { (deps: SelectListStateProcessor.Deps) -> SelectListImpl(deps) }

    // --- TodoDraft ----------------------------------------------------------------------------
    factory<DraftContainer> { (params: TodoDraft.Params) -> DraftImpl(params) }
    factory<SetDraftTextStateProcessor> { (deps: SetDraftTextStateProcessor.Deps) -> SetDraftTextImpl(deps) }
}

// === TodoDomain ================================================================================

private class TotalAddedImpl : TotalAddedContainer(CoroutineScope(Dispatchers.Main + SupervisorJob())) {
    override val source = flowResource(0)
    // As the DSL container: pipeline on Main, every value logged.
    override fun onEach(value: Int) = Logger.d(tag = "TodoDomainState") { value.toString() }
}

private class CountAddedTodosImpl(deps: Deps) : CountAddedTodos(deps) {
    override suspend fun process(event: AddTodo, state: Int) = state + 1
}

/** Runs right after [CountAddedTodosImpl] on the same AddTodo (declaration order in the spec),
 * reading the tally it just wrote. */
private class AddLimitCheck(deps: Deps) : AddTodoEventCascader(deps) {
    override fun cascade(input: AddTodo): List<Event> =
        if (totalAdded.value >= ADD_LIMIT) listOf(AddLimitReached(totalAdded.value)) else emptyList()
}

// === Work / Personal ===========================================================================

/** Reads from memory; the JSON file is read once to hydrate, then only written - per list
 * ([TodoListBase.Params.cacheKey]). */
private class TodosImpl(params: TodoListBase.Params, json: Json) : TodosContainer() {
    override val source = cacheJsonResource(key = params.cacheKey, initial = emptyList<Todo>(), json = json)
}

private class ShowCompletedImpl : ShowCompletedContainer() {
    override val source = flowResource(true)
}

private class LimitHitsImpl : LimitHitsContainer() {
    override val source = flowResource(0)
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

private class ToggleTodoImpl(deps: Deps) : ToggleTodoStateProcessor(deps) {
    override suspend fun process(event: ToggleTodo, state: List<Todo>) =
        state.map { if (it.id == event.id) it.copy(done = !it.done) else it }
}

private class DeleteTodoImpl(deps: Deps) : DeleteTodoStateProcessor(deps) {
    override suspend fun process(event: DeleteTodo, state: List<Todo>) = state.filterNot { it.id == event.id }
}

private class SaveTodoTextImpl(deps: Deps) : SaveTodoTextStateProcessor(deps) {
    override suspend fun process(event: SaveTodoText, state: List<Todo>) =
        state.map { if (it.id == event.id) it.copy(text = event.text) else it }
}

private class SetShowCompletedImpl(deps: Deps) : SetShowCompletedStateProcessor(deps) {
    override suspend fun process(event: SetShowCompleted, state: Boolean) = event.show
}

/** Materializes TodoDomain's verdict locally - routed here (`consumes`), never read from
 * TodoDomain's own container. */
private class CountLimitHitsImpl(deps: Deps) : AddLimitReachedStateProcessor(deps) {
    override suspend fun process(event: AddLimitReached, state: Int) = state + 1
}

private class LogLifecycleImpl : LifecycleEventsSideEffect() {
    override suspend fun handle(event: LifecycleEvents) = Logger.d(tag = "LifecycleEvents") { event.toString() }
}

// === TodoTabs ==================================================================================

private class SelectedListImpl : SelectedListContainer() {
    override val source = flowResource("Work")
}

private class SelectListImpl(deps: Deps) : SelectListStateProcessor(deps) {
    override suspend fun process(event: SelectList, state: String) = event.name
}

// === TodoDraft =================================================================================

/** On Main.immediate (as the DSL one): the text field's value must update in the same frame. */
private class DraftImpl(params: TodoDraft.Params) :
    DraftContainer(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    override val source = flowResource(params.initialText)
}

private class SetDraftTextImpl(deps: Deps) : SetDraftTextStateProcessor(deps) {
    override suspend fun process(event: SetDraftText, state: String) = event.text
}
