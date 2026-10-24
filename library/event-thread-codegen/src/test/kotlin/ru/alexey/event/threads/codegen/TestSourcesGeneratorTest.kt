package ru.alexey.event.threads.codegen

import com.squareup.kotlinpoet.FileSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import ru.alexey.event.threads.codegen.generator.TypedScopeSources
import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.EventModel
import ru.alexey.event.threads.codegen.model.ExampleModel
import ru.alexey.event.threads.codegen.model.HandlerModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TestSourcesGeneratorTest {

    private fun tests(vararg scopes: ScopeModel) = TypedScopeSources.generateTests("com.example", scopes.toList())
    private fun List<FileSpec>.file(name: String) = single { it.name == name }
    private fun flat(source: Any) = source.toString().replace(Regex("\\s+"), " ")
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val todos = ArgDef.Generics("List", listOf(ArgDef.Primitive("todo", "com.example.Todo")), field = "todos")
    private val filter = ArgDef.Primitive("filter", "com.example.Filter")
    private val params = ArgDef.Created("ListParams", listOf(ArgDef.Primitive("name", "String")))

    private fun list(vararg handlers: HandlerModel) = ScopeModel("List", params = params, handlers = handlers.toList())

    @Test
    fun contractAndProbePerScopeInTheScopesPackage() {
        val files = tests(list(HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(todos))))

        assertEquals(setOf("com.example.scopes.ListScopeContract", "com.example.scopes.ListScopeProbe"), files.map { "${it.packageName}.${it.name}" }.toSet())
    }

    @Test
    fun contractAsksForBindingsAndParamsAndChecksWiring() {
        val source = flat(tests(list(HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(todos)))).file("ListScopeContract"))

        assertContains(source, "public abstract class ListScopeContract")
        assertContains(source, "protected abstract fun bindings(): ListScopeBindings")
        assertContains(source, "protected abstract fun params(): ListParams")
        assertContains(source, "@Test public fun everyBindingIsProvided() { val scope = ListScope(params(), bindings()) try { scope.eventBus } finally { scope.close() } }")
        assertContains(source, "assertEquals(1, handled[Add::class.simpleName]?.actions?.size ?: 0, \"handlers registered for Add\")")
    }

    @Test
    fun eventFlowIsCheckedThroughTheLiveScopesGraph() {
        val count = ArgDef.Primitive("count", "Int")
        val scope = ScopeModel("Tally", handlers = listOf(
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(count)),
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Event(EventModel("com.example.Limit"))),
            HandlerModel(EventModel("com.example.Limit")),
        ))

        val source = flat(tests(scope).file("TallyScopeContract"))

        assertContains(source, "val graph = scope.metadata.toEventGraph(\"Tally\")")
        assertContains(source, "assertEquals(setOf<String>(\"Limit\"), events[Add::class.simpleName]?.producedTypes.orEmpty(), \"events Add cascades into\")")
        assertContains(source, "graph.assertReaches(\"Tally\", \"Add\", \"Limit\")")
        assertContains(source, "assertEquals(setOf<String>(), events[Limit::class.simpleName]?.producedTypes.orEmpty()")
        // two handlers for Add, both counted
        assertContains(source, "assertEquals(2, handled[Add::class.simpleName]?.actions?.size ?: 0")
    }

    @Test
    fun relationsTestUsesTheGeneratedRegistration() {
        val rules = ScopeModel("Rules", handlers = listOf(HandlerModel(EventModel("com.example.Tick"), HandlerOutput.Event(EventModel("com.example.Limit")))))
        val board = ScopeModel("Board", dependsOn = listOf("Rules"), consumes = listOf("com.example.Limit"), handlers = listOf(HandlerModel(EventModel("com.example.Limit"))))

        val source = flat(tests(rules, board).file("BoardScopeContract"))

        assertContains(source, "val holder = scopeHolder { registerBoardScope { _ -> bindings() } }")
        assertContains(source, "assertEquals(listOf<String>(RulesScopeKey.identity), holder.dependencies[\"Board\"].orEmpty())")
        assertContains(source, "assertTrue(\"Board\" in holder.external[Limit::class].orEmpty()")
    }

    @Test
    fun stateExampleStubsTheContainerAndChecksTheNewState() {
        val toggle = HandlerModel(
            EventModel("com.example.Toggle"), HandlerOutput.Data(todos),
            examples = listOf(ExampleModel(
                name = "marksDone",
                given = json("""[{ "id": 1, "done": false }]"""),
                event = json("""{ "id": 1 }"""),
                expect = json("""[{ "id": 1, "done": true }]"""),
            )),
        )

        val source = flat(tests(list(toggle)).file("ListScopeContract"))

        assertContains(source, "public fun toggleStateProcessor_example_marksDone(): TestResult = runTest {")
        assertContains(source, "val todos = object : TodosContainer() { override val source = flowResource<List<Todo>>(listOf<Todo>(Todo(id = 1, done = false))) }")
        assertContains(source, "val handler = bindings().toggleStateProcessor(ToggleStateProcessor.Deps(todos), params())")
        assertContains(source, "handler.handle(Toggle(id = 1))")
        assertContains(source, "assertEquals(listOf<Todo>(Todo(id = 1, done = true)), todos.value)")
    }

    @Test
    fun cascadeExampleStubsReadsAndComparesThePreview() {
        val count = ArgDef.Primitive("count", "Int")
        val scope = ScopeModel("Tally", handlers = listOf(
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(count)),
            HandlerModel(
                EventModel("com.example.Add"), HandlerOutput.Event(EventModel("com.example.Limit")), reads = listOf(count),
                examples = listOf(ExampleModel(name = "atLimit", reads = mapOf("count" to json("5")), event = json("{}"), emits = listOf(json("""{ "at": 5 }""")))),
            ),
            HandlerModel(EventModel("com.example.Limit")),
        ))

        val source = flat(tests(scope).file("TallyScopeContract"))

        assertContains(source, "val count = object : CountContainer() { override val source = flowResource<Int>(5) }")
        assertContains(source, "val handler = bindings().addEventCascader(AddEventCascader.Deps(count.snapshot()))")
        assertContains(source, "assertEquals(listOf<Event>(Limit(at = 5)), handler.preview(Add()))")
    }

    @Test
    fun definedTypesAreBuiltTypedAndEnumsFromStrings() {
        val item = ArgDef.Created("Item", listOf(ArgDef.Primitive("label", "String"), ArgDef.Primitive("weight", "Float"), ArgDef.Primitive("kind", "com.example.Kind")), field = "item")
        val scope = ScopeModel("Box", handlers = listOf(HandlerModel(
            EventModel("Put", listOf(ArgDef.Primitive("tags", "String"))), HandlerOutput.Data(item),
            examples = listOf(ExampleModel(
                given = json("""{ "label": "a", "weight": 1.5, "kind": "SMALL" }"""),
                event = json("""{ "tags": "x" }"""),
                expect = json("""{ "label": "b", "weight": 2, "kind": "LARGE" }"""),
            )),
        )))

        val source = flat(tests(scope).file("BoxScopeContract"))

        assertContains(source, "Item(label = \"a\", weight = 1.5f, kind = Kind.SMALL)")
        assertContains(source, "handler.handle(Put(tags = \"x\"))")
        assertContains(source, "public fun putStateProcessor_example_0()")
    }

    @Test
    fun mapsAreBuiltWithTypedKeysAndValues() {
        val counts = ArgDef.Generics("Map", listOf(ArgDef.Primitive("k", "String"), ArgDef.Primitive("v", "Int")), field = "counts")
        val scope = ScopeModel("Stats", handlers = listOf(HandlerModel(
            EventModel("com.example.Bump"), HandlerOutput.Data(counts),
            examples = listOf(ExampleModel(given = json("""{ "a": 1 }"""), expect = json("""{ "a": 2 }"""))),
        )))

        assertContains(flat(tests(scope).file("StatsScopeContract")), "mapOf<String, Int>(\"a\" to 2)")
    }

    @Test
    fun nestedObjectOfAnUnknownTypeIsRejectedWithItsPath() {
        val bad = HandlerModel(
            EventModel("com.example.Set"), HandlerOutput.Data(todos),
            examples = listOf(ExampleModel(name = "nested", given = json("""[{ "owner": { "id": 1 } }]"""), expect = json("[]"))),
        )

        val error = assertFailsWith<IllegalArgumentException> { tests(list(bad)) }
        assertContains(error.message.orEmpty(), "Example List.SetStateProcessor[nested].todos.owner")
        assertContains(error.message.orEmpty(), "describe it as a `created` type")
    }

    @Test
    fun probeGivesTypedAccessToTheScopesContainers() {
        val show = ArgDef.Primitive("showDone", "Boolean")
        val source = flat(tests(list(
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(todos)),
            HandlerModel(EventModel("com.example.Show"), HandlerOutput.Data(show)),
        )).file("ListScopeProbe"))

        assertContains(source, "public class ListScopeProbe private constructor( private val recording: Recording, params: ListParams, ) : TypedScopeProbe(ListScope(params, recording))")
        assertContains(source, "public constructor(params: ListParams, bindings: ListScopeBindings) : this(Recording(bindings), params)")
        assertContains(source, "public val todos: TodosContainer get() = checkNotNull(recording.todos)")
        assertContains(source, "public val showDone: ShowDoneContainer get() = checkNotNull(recording.showDone)")
        assertContains(source, "private class Recording( private val wrapped: ListScopeBindings, ) : ListScopeBindings by wrapped")
        assertContains(source, "override fun todos(params: ListParams): TodosContainer = wrapped.todos(params).also { todos = it }")
    }
}
