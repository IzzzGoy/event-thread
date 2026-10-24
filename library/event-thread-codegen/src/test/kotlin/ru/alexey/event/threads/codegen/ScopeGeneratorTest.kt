package ru.alexey.event.threads.codegen

import com.squareup.kotlinpoet.FileSpec
import ru.alexey.event.threads.OutputMismatchBehavior
import ru.alexey.event.threads.Privacy
import ru.alexey.event.threads.codegen.generator.ScopeGenerator
import ru.alexey.event.threads.codegen.generator.TypedScopeSources
import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.DerivedModel
import ru.alexey.event.threads.codegen.model.EventModel
import ru.alexey.event.threads.codegen.model.HandlerModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScopeGeneratorTest {

    private fun generate(vararg scopes: ScopeModel) = TypedScopeSources.generate("com.example", scopes.toList())

    private fun List<FileSpec>.file(qualified: String) = single { "${it.packageName}.${it.name}" == qualified }

    /** KotlinPoet wraps long declarations; compare whitespace-normalised source. */
    private fun flat(source: Any) = source.toString().replace(Regex("\\s+"), " ")

    private val listParams = ArgDef.Created("TodoListParams", listOf(ArgDef.Primitive("listName", "String")))
    private val todos = ArgDef.Generics("List", listOf(ArgDef.Primitive("todo", "com.example.Todo")))
    private val draft = ArgDef.Primitive("draft", "String")
    private val setDraft = EventModel("SetDraft", listOf(ArgDef.Primitive("text", "String")))

    private val todoScope = ScopeModel(
        name = "Todo",
        params = listParams,
        handlers = listOf(
            HandlerModel(EventModel("AddTodo", listOf(ArgDef.Primitive("text", "String"))), HandlerOutput.Data(todos)),
            HandlerModel(setDraft, HandlerOutput.Data(draft)),
            HandlerModel(
                EventModel("com.example.SelectList"),
                HandlerOutput.Event(setDraft, OutputMismatchBehavior.Warning),
                privacy = Privacy.private,
                reads = listOf(draft),
            ),
            HandlerModel(EventModel("Log")),
        ),
    )

    @Test
    fun eachKindGoesToItsPackage() {
        val files = generate(todoScope).map { "${it.packageName}.${it.name}" }.toSet()

        assertEquals(
            setOf(
                "com.example.scopes.TodoScope",
                "com.example.events.AddTodoHandler", "com.example.events.SetDraftHandler",
                "com.example.events.SelectListHandler", "com.example.events.LogHandler",
                "com.example.containers.TodoListContainer", "com.example.containers.DraftContainer",
                "com.example.models.AddTodo", "com.example.models.SetDraft", "com.example.models.Log",
                "com.example.models.TodoListParams",
            ),
            files,
        )
    }

    @Test
    fun scopeWithoutParamsIsKeyedByUnitKey() {
        val source = flat(generate(ScopeModel("Settings")).file("com.example.scopes.SettingsScope"))

        assertContains(source, "public object SettingsScopeKey : ScopeKey<Unit> { override val identity: String = \"Settings\" }")
        assertContains(source, "public class SettingsScope( private val bindings: SettingsScopeBindings, dependencyProvider: DependencyProvider = DummyProvider(), ) : ScopeTyped<Unit>(scopeKey = SettingsScopeKey, params = Unit, dependencyProvider = dependencyProvider, eventBusFactory = { bindings.eventBus() })")
        assertContains(source, "public constructor(dependencyProvider: DependencyProvider) : this(SettingsScopeDependencyBindings(dependencyProvider), dependencyProvider)")
    }

    @Test
    fun scopeIsKeyedByParamsFromModels() {
        val source = flat(generate(todoScope.copy(description = "Todo list screen")).file("com.example.scopes.TodoScope"))

        assertContains(source, "import com.example.models.TodoListParams")
        assertContains(source, "public object TodoScopeKey : ScopeKey<TodoListParams>")
        assertContains(source, "public class TodoScope( params: TodoListParams, private val bindings: TodoScopeBindings, dependencyProvider: DependencyProvider = DummyProvider(), ) : ScopeTyped<TodoListParams>(scopeKey = TodoScopeKey, params = params, description = \"Todo list screen\", dependencyProvider = dependencyProvider, eventBusFactory = { bindings.eventBus() })")
    }

    @Test
    fun wireResolvesContainersAndHandlersFromTheirPackages() {
        val source = flat(generate(todoScope).file("com.example.scopes.TodoScope"))

        assertContains(source, "import com.example.containers.TodoListContainer")
        assertContains(source, "import com.example.events.AddTodoStateProcessor")
        assertContains(source, "val todoList = container(\"todoList\", bindings.todoList(params))")
        assertContains(source, "val draft = container(\"draft\", bindings.draft(params))")
        assertContains(source, "on<AddTodo>(bindings.addTodoStateProcessor(AddTodoStateProcessor.Deps(todoList), params))")
        assertContains(source, "on<SelectList>(bindings.selectListEventCascader(SelectListEventCascader.Deps(draft.snapshot()), params))")
        assertContains(source, "on<Log>(bindings.logSideEffect(params))")
    }

    @Test
    fun handlerBasesCarryTypedDepsAndMetadata() {
        val files = generate(todoScope)
        val add = flat(files.file("com.example.events.AddTodoHandler"))
        val select = flat(files.file("com.example.events.SelectListHandler"))

        assertContains(add, "public abstract class AddTodoStateProcessor( deps: Deps, ) : EventHandler.StateProcessor<AddTodo, List<Todo>>(deps.todoList) { public class Deps( public val todoList: TodoListContainer, ) }")
        assertContains(select, "public class Deps( public val draft: ContainerSnapshot<DraftContainer, String>, )")
        assertContains(select, "protected val draft: ContainerSnapshot<DraftContainer, String> = deps.draft")
        assertContains(select, "final override val outputs: Set<KClass<out Event>> = setOf(SetDraft::class)")
        assertContains(select, "override val onMismatch: OutputMismatchBehavior = OutputMismatchBehavior.Warning")
        assertContains(select, "override val privacy: Privacy = Privacy.private")
        assertContains(flat(files.file("com.example.events.LogHandler")), "public abstract class LogSideEffect : EventHandler.SideEffect<Log>()")
    }

    @Test
    fun containersAreAbstractOverTheirState() {
        val files = generate(todoScope)

        assertContains(flat(files.file("com.example.containers.TodoListContainer")), "public abstract class TodoListContainer( coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default), ) : DatacontainerTyped<List<Todo>>(coroutineScope)")
    }

    @Test
    fun scopesShareContainersAndIdenticalHandlers() {
        val add = HandlerModel(EventModel("com.example.AddTodo"), HandlerOutput.Data(todos))
        val files = generate(ScopeModel("Work", handlers = listOf(add)), ScopeModel("Personal", handlers = listOf(add)))

        assertEquals(1, files.count { it.name == "TodoListContainer" })
        assertEquals(1, files.count { it.name == "AddTodoHandler" })
        assertContains(flat(files.file("com.example.scopes.PersonalScope")), "on<AddTodo>(bindings.addTodoStateProcessor(AddTodoStateProcessor.Deps(todoList)))")
    }

    @Test
    fun differentKindsForOneEventShareItsHandlerFile() {
        val files = generate(
            ScopeModel("Todo", handlers = listOf(HandlerModel(EventModel("com.example.AddTodo"), HandlerOutput.Data(todos)))),
            ScopeModel("Rules", handlers = listOf(
                HandlerModel(EventModel("com.example.AddTodo"), HandlerOutput.Event(EventModel("com.example.Limit"))),
                HandlerModel(EventModel("com.example.Limit")),
            )),
        )
        val source = flat(files.file("com.example.events.AddTodoHandler"))

        assertContains(source, "public abstract class AddTodoStateProcessor")
        assertContains(source, "public abstract class AddTodoEventCascader")
    }

    @Test
    fun sameHandlerNameDefinedDifferentlyIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            generate(
                ScopeModel("Work", handlers = listOf(HandlerModel(EventModel("com.example.Set"), HandlerOutput.Data(todos)))),
                ScopeModel("Personal", handlers = listOf(HandlerModel(EventModel("com.example.Set"), HandlerOutput.Data(draft)))),
            )
        }
        assertContains(error.message.orEmpty(), "SetStateProcessor is defined differently in scopes [Work, Personal]")
    }

    @Test
    fun sameStateSpelledDifferentlyIsRejected() {
        val full = ArgDef.Generics("List", listOf(ArgDef.Created("Todo", listOf(ArgDef.Primitive("id", "Long")))))
        val bare = ArgDef.Generics("List", listOf(ArgDef.Created("Todo")))
        val scope = ScopeModel("Todo", handlers = listOf(
            HandlerModel(EventModel("A"), HandlerOutput.Data(full)),
            HandlerModel(EventModel("B"), HandlerOutput.Data(bare)),
        ))

        val error = assertFailsWith<IllegalArgumentException> { generate(scope) }
        assertContains(error.message.orEmpty(), "share a container name [TodoList]")
    }

    @Test
    fun definedEventsGoToModelsReferencesNot() {
        val files = generate(todoScope)

        assertContains(flat(files.file("com.example.models.SetDraft")), "public data class SetDraft( public val text: String, ) : Event")
        assertContains(flat(files.file("com.example.models.Log")), "public data object Log : Event")
        assertEquals(0, files.count { it.name == "SelectList" })
    }

    @Test
    fun conflictingEventDefinitionsAreRejected() {
        val scope = ScopeModel("Todo", handlers = listOf(
            HandlerModel(EventModel("A"), HandlerOutput.Event(EventModel("B", listOf(ArgDef.Primitive("x", "Int"))))),
            HandlerModel(EventModel("B")),
        ))

        assertFailsWith<IllegalArgumentException> { generate(scope) }
    }

    @Test
    fun createdParamsAreGeneratedOnceAsDataClass() {
        val files = generate(ScopeModel("Todo", params = listParams), ScopeModel("Archive", params = listParams))

        assertContains(flat(files.file("com.example.models.TodoListParams")), "public data class TodoListParams( public val listName: String, )")
    }

    @Test
    fun referencedOrBuiltInParamsGenerateNoModel() {
        val files = generate(
            ScopeModel("Item", params = ArgDef.Created("ItemId")),
            ScopeModel("Tags", params = ArgDef.Generics("List", listOf(ArgDef.Primitive("tag", "String")))),
        )

        assertEquals(setOf("ItemScope", "TagsScope"), files.map { it.name }.toSet())
        assertContains(flat(files.file("com.example.scopes.TagsScope")), "ScopeKey<List<String>>")
    }

    @Test
    fun conflictingParamsDefinitionsAreRejected() {
        val other = ArgDef.Created("TodoListParams", listOf(ArgDef.Primitive("id", "Long")))

        assertFailsWith<IllegalArgumentException> { generate(ScopeModel("Todo", params = listParams), ScopeModel("Archive", params = other)) }
    }

    @Test
    fun unnamedModelFieldIsRejected() {
        val bad = ArgDef.Created("Bad", listOf(ArgDef.Generics("List", listOf(ArgDef.Primitive("x", "Int")))))

        val error = assertFailsWith<IllegalArgumentException> { generate(ScopeModel("Todo", params = bad)) }
        assertContains(error.message.orEmpty(), "has no name - set its `field`")
    }

    @Test
    fun namedNonPrimitiveFieldsBecomeProperties() {
        val params = ArgDef.Created("Filter", listOf(
            ArgDef.Generics("List", listOf(ArgDef.Primitive("tag", "String")), field = "tags"),
            ArgDef.Created("Range", listOf(ArgDef.Primitive("from", "Int")), field = "range"),
        ))

        val source = flat(generate(ScopeModel("Search", params = params)).file("com.example.models.Filter"))

        assertContains(source, "public data class Filter( public val tags: List<String>, public val range: Range, )")
    }

    @Test
    fun fieldNameNamesTheContainer() {
        val todosField = ArgDef.Generics("List", listOf(ArgDef.Primitive("todo", "com.example.Todo")), field = "todos")
        val files = generate(ScopeModel("Todo", handlers = listOf(HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(todosField)))))

        assertContains(flat(files.file("com.example.containers.TodosContainer")), "DatacontainerTyped<List<Todo>>")
        assertContains(flat(files.file("com.example.scopes.TodoScope")), "val todos = container(\"todos\", bindings.todos())")
    }

    private val count = ArgDef.Primitive("count", "Int")
    private val total = ArgDef.Primitive("total", "Int")

    @Test
    fun severalHandlersPerEventGetTheirOwnBases() {
        val scope = ScopeModel("Tally", handlers = listOf(
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(count)),
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(total), name = "AddToTotal"),
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Event(EventModel("com.example.Limit")), reads = listOf(total)),
            HandlerModel(EventModel("com.example.Limit")),
        ))
        val files = generate(scope)
        val wire = flat(files.file("com.example.scopes.TallyScope"))
        val bases = flat(files.file("com.example.events.AddHandler"))

        assertContains(bases, "public abstract class AddStateProcessor")
        assertContains(bases, "public abstract class AddToTotal")
        assertContains(bases, "public abstract class AddEventCascader")
        // declaration order = registration order = run order
        val order = listOf("bindings.addStateProcessor(", "bindings.addToTotal(", "bindings.addEventCascader(").map { wire.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun twoSameKindHandlersForOneEventNeedAName() {
        val scope = ScopeModel("Tally", handlers = listOf(
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(count)),
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(total)),
        ))

        val error = assertFailsWith<IllegalArgumentException> { generate(scope) }
        assertContains(error.message.orEmpty(), "or give one of them a `name`")
    }

    @Test
    fun derivedContainerGetsDepsAndIsResolvedAfterItsSources() {
        val todos = ArgDef.Generics("List", listOf(ArgDef.Primitive("todo", "com.example.Todo")), field = "todos")
        val show = ArgDef.Primitive("showCompleted", "Boolean")
        val visible = ArgDef.Primitive("visibleTodos", "com.example.VisibleTodos")
        val scope = ScopeModel(
            "List",
            handlers = listOf(
                HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(todos)),
                HandlerModel(EventModel("com.example.Show"), HandlerOutput.Data(show)),
            ),
            derived = listOf(DerivedModel(visible, listOf(todos, show))),
        )
        val files = generate(scope)
        val container = flat(files.file("com.example.containers.VisibleTodosContainer"))
        val wire = flat(files.file("com.example.scopes.ListScope"))

        assertContains(container, "public abstract class VisibleTodosContainer( deps: Deps, coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default), ) : DatacontainerTyped<VisibleTodos>(coroutineScope)")
        assertContains(container, "public class Deps( public val todos: TodosContainer, public val showCompleted: ShowCompletedContainer, )")
        assertContains(container, "protected val todos: TodosContainer = deps.todos")
        assertContains(wire, "val visibleTodos = container(\"visibleTodos\", bindings.visibleTodos(VisibleTodosContainer.Deps(todos, showCompleted)))")
        assertTrue(wire.indexOf("val visibleTodos") > wire.indexOf("val showCompleted"))
    }

    @Test
    fun stateDerivedInOneScopeAndWrittenInAnotherIsRejected() {
        val a = ArgDef.Primitive("a", "Int")
        val view = ArgDef.Primitive("view", "Int")
        val error = assertFailsWith<IllegalArgumentException> {
            generate(
                ScopeModel("One", handlers = listOf(HandlerModel(EventModel("com.example.X"), HandlerOutput.Data(a))), derived = listOf(DerivedModel(view, listOf(a)))),
                ScopeModel("Two", handlers = listOf(HandlerModel(EventModel("com.example.Y"), HandlerOutput.Data(view)))),
            )
        }
        assertContains(error.message.orEmpty(), "ViewContainer is written by handlers in one scope and derived")
    }

    private val rules = ScopeModel(
        name = "Rules",
        consumes = listOf("com.example.AddTodo"),
        handlers = listOf(HandlerModel(EventModel("com.example.AddTodo"), HandlerOutput.Event(EventModel("com.example.Limit")))),
    )

    @Test
    fun relationsBecomeAFragmentAndARegistration() {
        val board = ScopeModel(
            name = "Board",
            dependsOn = listOf("Rules", "com.example.legacy.AuthScopeKey"),
            consumes = listOf("com.example.Limit"),
            handlers = listOf(HandlerModel(EventModel("com.example.Limit"))),
        )
        val source = flat(generate(board, rules).file("com.example.scopes.BoardScope"))

        assertContains(source, "public val BoardScopeFragment: ScopeKeyFragment<Unit> = ScopeKey.extend(BoardScopeKey) { dependsOn(RulesScopeKey) dependsOn(AuthScopeKey) consume<Limit>() }")
        assertContains(source, "import com.example.legacy.AuthScopeKey")
        assertContains(source, "public fun ScopeHolderBuilder.registerBoardScope() { scopeTyped(BoardScopeKey) { provider -> BoardScope(provider) } include(BoardScopeFragment) }")
        assertContains(source, "public fun ScopeHolderBuilder.registerBoardScope(bindings: (DependencyProvider) -> BoardScopeBindings) { scopeTyped(BoardScopeKey) { provider -> BoardScope(bindings(provider), provider) } include(BoardScopeFragment) }")
    }

    @Test
    fun scopeWithoutRelationsStillGetsAnEmptyFragment() {
        val source = flat(generate(ScopeModel("Settings")).file("com.example.scopes.SettingsScope"))

        assertContains(source, "ScopeKey.extend(SettingsScopeKey) { }")
    }

    @Test
    fun dependsOnAnUnknownScopeIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> { generate(ScopeModel("Board", dependsOn = listOf("Nope"))) }

        assertContains(error.message.orEmpty(), "dependsOn 'Nope', which no spec declares")
    }

    @Test
    fun dependsOnAScopeWithParamsIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            generate(ScopeModel("Board", dependsOn = listOf("Todo")), ScopeModel("Todo", params = listParams))
        }

        assertContains(error.message.orEmpty(), "dependsOn 'Todo', which needs params")
    }

    @Test
    fun bindingsInterfaceHasOneFactoryPerContainerAndHandler() {
        val source = flat(generate(todoScope).file("com.example.scopes.TodoScope"))

        assertContains(source, "public interface TodoScopeBindings {")
        assertContains(source, "public fun todoList(params: TodoListParams): TodoListContainer")
        assertContains(source, "public fun draft(params: TodoListParams): DraftContainer")
        assertContains(source, "public fun addTodoStateProcessor(deps: AddTodoStateProcessor.Deps, params: TodoListParams): AddTodoStateProcessor")
        assertContains(source, "public fun selectListEventCascader(deps: SelectListEventCascader.Deps, params: TodoListParams): SelectListEventCascader")
        assertContains(source, "public fun logSideEffect(params: TodoListParams): LogSideEffect")
        assertContains(source, "public fun eventBus(): EventBus? = null")
    }

    @Test
    fun dependencyAdapterResolvesEachBindingWithDepsThenParams() {
        val source = flat(generate(todoScope).file("com.example.scopes.TodoScope"))

        assertContains(source, "public class TodoScopeDependencyBindings( dependencyProvider: DependencyProvider, ) : DependencyProviderBindings(dependencyProvider, \"Todo\"), TodoScopeBindings")
        assertContains(source, "override fun addTodoStateProcessor(deps: AddTodoStateProcessor.Deps, params: TodoListParams): AddTodoStateProcessor = resolve(AddTodoStateProcessor::class, deps, params)")
        assertContains(source, "override fun todoList(params: TodoListParams): TodoListContainer = resolve(TodoListContainer::class, params)")
        assertContains(source, "override fun eventBus(): EventBus? = resolveEventBus()")
        assertContains(source, "public constructor(params: TodoListParams, dependencyProvider: DependencyProvider) : this(params, TodoScopeDependencyBindings(dependencyProvider), dependencyProvider)")
    }

    @Test
    fun registrationFromBindingsIsGeneratedNextToTheDependencyOne() {
        val source = flat(generate(todoScope).file("com.example.scopes.TodoScope"))

        assertContains(source, "public fun ScopeHolderBuilder.registerTodoScope() { scopeTyped(TodoScopeKey) { params, provider -> TodoScope(params, provider) } include(TodoScopeFragment) }")
        assertContains(source, "public fun ScopeHolderBuilder.registerTodoScope(bindings: (TodoListParams, DependencyProvider) -> TodoScopeBindings) { scopeTyped(TodoScopeKey) { params, provider -> TodoScope(params, bindings(params, provider), provider) } include(TodoScopeFragment) }")
    }

    private val limit = EventModel("com.example.Limit")
    private fun emitter(name: String = "Rules") =
        ScopeModel(name, handlers = listOf(HandlerModel(EventModel("com.example.Add"), HandlerOutput.Event(limit))))

    @Test
    fun cascadeNobodyHandlesIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> { generate(emitter()) }

        assertContains(error.message.orEmpty(), "com.example.Limit, cascaded by 'Rules' on com.example.Add, has no handler in any scope")
    }

    @Test
    fun cascadeHandledElsewhereButNotConsumedIsRejected() {
        val ui = ScopeModel("Ui", handlers = listOf(HandlerModel(limit)))

        val error = assertFailsWith<IllegalArgumentException> { generate(emitter(), ui) }

        assertContains(error.message.orEmpty(), "never reaches a handler: [Ui] handle it but don't consume it")
    }

    @Test
    fun cascadeConsumedByAnotherScopeIsFine() {
        val ui = ScopeModel("Ui", consumes = listOf("com.example.Limit"), handlers = listOf(HandlerModel(limit)))

        generate(emitter(), ui)
    }

    @Test
    fun allFlowProblemsAreReportedTogether() {
        val error = assertFailsWith<IllegalArgumentException> { generate(emitter("First"), emitter("Second")) }

        assertContains(error.message.orEmpty(), "cascaded by 'First'")
        assertContains(error.message.orEmpty(), "cascaded by 'Second'")
    }

    @Test
    fun trailingScopeSuffixIsNotDoubled() {
        assertEquals("TodoScope", ScopeGenerator.className(ScopeModel("TodoScope")))
    }

    @Test
    fun rejectsClashingNames() {
        assertFailsWith<IllegalArgumentException> { generate(ScopeModel("Todo"), ScopeModel("TodoScope")) }
    }

    @Test
    fun rejectsInvalidIdentifier() {
        assertFailsWith<IllegalArgumentException> { ScopeModel("my-scope") }
    }
}
