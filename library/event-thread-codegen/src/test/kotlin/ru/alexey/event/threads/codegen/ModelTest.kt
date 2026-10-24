package ru.alexey.event.threads.codegen

import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.DerivedModel
import ru.alexey.event.threads.codegen.model.ExampleModel
import ru.alexey.event.threads.codegen.model.EventModel
import ru.alexey.event.threads.codegen.model.HandlerModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import kotlinx.serialization.SerializationException
import ru.alexey.event.threads.OutputMismatchBehavior
import ru.alexey.event.threads.Privacy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ModelTest {

    private val item = ArgDef.Created(
        name = "TodoItem",
        fields = listOf(
            ArgDef.Primitive("title", "String"),
            ArgDef.Generics("List", listOf(ArgDef.Primitive("tags", "kotlin.String"))),
        ),
    )

    private val scope = ScopeModel(
        name = "Todo",
        handlers = listOf(
            HandlerModel(
                input = EventModel("AddTodo", listOf(item)),
                output = HandlerOutput.Data(ArgDef.Generics("List", listOf(item))),
            ),
            HandlerModel(
                input = EventModel("ClearAll"),
                output = HandlerOutput.Event(EventModel("TodosCleared")),
            ),
            HandlerModel(input = EventModel("Log")),
        ),
    )

    private fun roundTrip(scope: ScopeModel): ScopeModel =
        ScopeSpecFormat.decode(ScopeSpecFormat.encode(listOf(scope))).single()

    @Test
    fun recursiveModelSurvivesSerialization() {
        assertEquals(scope, roundTrip(scope))
    }

    @Test
    fun noneOutputStaysSingleton() {
        assertSame(HandlerOutput.None, roundTrip(scope).handlers.last().output)
    }

    @Test
    fun decodesHandWrittenSpec() {
        val spec = """
            [{ "name": "Todo", "handlers": [
                { "input": { "name": "AddTodo", "args": [
                    { "kind": "generics", "baseClassName": "List", "parameters": [
                        { "kind": "created", "name": "TodoItem", "fields": [
                            { "kind": "primitive", "value": "title", "typeKey": "String" }
                        ] }
                    ] }
                ] }, "output": { "kind": "event", "event": { "name": "TodoAdded" } } },
                { "input": { "name": "Log" } }
            ] }]
        """.trimIndent()

        val handlers = ScopeSpecFormat.decode(spec).single().handlers
        val arg = handlers[0].input.args.single() as ArgDef.Generics
        assertEquals("TodoItem", (arg.parameters.single() as ArgDef.Created).name)
        assertEquals(HandlerOutput.Event(EventModel("TodoAdded")), handlers[0].output)
        assertSame(HandlerOutput.None, handlers[1].output)
    }

    @Test
    fun registrationMetadataDefaultsToTheDslsAndDecodes() {
        val defaults = ScopeSpecFormat.decode("""[{ "name": "Todo", "handlers": [
            { "input": { "name": "A" }, "output": { "kind": "event", "event": { "name": "B" } } }
        ] }]""").single().handlers.single()
        assertEquals(Privacy.public, defaults.privacy)
        assertEquals(false, defaults.override)
        assertEquals(OutputMismatchBehavior.Strict, (defaults.output as HandlerOutput.Event).onMismatch)

        val explicit = ScopeSpecFormat.decode("""[{ "name": "Todo", "handlers": [
            { "input": { "name": "A" }, "privacy": "private", "override": true,
              "output": { "kind": "event", "event": { "name": "B" }, "onMismatch": "Warning" } }
        ] }]""").single().handlers.single()
        assertEquals(Privacy.private, explicit.privacy)
        assertEquals(true, explicit.override)
        assertEquals(OutputMismatchBehavior.Warning, (explicit.output as HandlerOutput.Event).onMismatch)
    }

    @Test
    fun readsMustNameAContainerOfTheScope() {
        val draft = ArgDef.Primitive("draft", "String")
        val writesDraft = HandlerModel(EventModel("SetDraft"), HandlerOutput.Data(draft))

        val ok = ScopeModel("Todo", handlers = listOf(writesDraft, HandlerModel(EventModel("Select"), reads = listOf(draft))))
        assertEquals(listOf(draft), ok.handlers[1].reads)

        assertFailsWith<IllegalArgumentException> {
            ScopeModel("Todo", handlers = listOf(writesDraft, HandlerModel(EventModel("Select"), reads = listOf(ArgDef.Primitive("other", "String")))))
        }
    }

    @Test
    fun consumingAnEventWithoutHandlerIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            ScopeModel("Todo", consumes = listOf("AddTodo"), handlers = listOf(HandlerModel(EventModel("Other"))))
        }

        assertContains(error.message.orEmpty(), "consumes [AddTodo] but has no handler for it")
    }

    @Test
    fun relationsDecode() {
        val scope = ScopeSpecFormat.decode("""[{ "name": "Todo", "dependsOn": ["Rules"], "consumes": ["A"],
            "handlers": [{ "input": { "name": "A" } }] }]""").single()

        assertEquals(listOf("Rules"), scope.dependsOn)
        assertEquals(listOf("A"), scope.consumes)
    }

    @Test
    fun invalidSpecIsRejectedOnDecode() {
        // `init` validation runs for deserialized models too, not just hand-built ones.
        assertFailsWith<IllegalArgumentException> { ScopeSpecFormat.decode("""[{ "name": "my-scope" }]""") }
        assertFailsWith<SerializationException> {
            ScopeSpecFormat.decode("""[{ "name": "Todo", "handlers": [{ "input": { "name": "A" }, "output": { "kind": "nope" } }] }]""")
        }
    }

    @Test
    fun rejectsInvalidNames() {
        assertFailsWith<IllegalArgumentException> { EventModel("add-todo") }
        assertFailsWith<IllegalArgumentException> { ArgDef.Created("Todo Item", emptyList()) }
        assertFailsWith<IllegalArgumentException> { ArgDef.Primitive("title", "kotlin..String") }
        assertFailsWith<IllegalArgumentException> { ArgDef.Generics("List", emptyList()) }
    }

    @Test
    fun severalHandlersPerEventAreAllowed() {
        val scope = ScopeModel("Todo", handlers = listOf(HandlerModel(EventModel("A")), HandlerModel(EventModel("A"), name = "AlsoA")))

        assertEquals(2, scope.handlers.size)
    }

    @Test
    fun derivedMustComeFromKnownContainers() {
        val list = ArgDef.Primitive("items", "Int")
        val writes = HandlerModel(EventModel("Set"), HandlerOutput.Data(list))
        val view = ArgDef.Primitive("view", "Int")

        ScopeModel("S", handlers = listOf(writes), derived = listOf(DerivedModel(view, listOf(list))))
        assertFailsWith<IllegalArgumentException> {
            ScopeModel("S", handlers = listOf(writes), derived = listOf(DerivedModel(view, listOf(ArgDef.Primitive("nope", "Int")))))
        }
        assertFailsWith<IllegalArgumentException> { // written by a handler AND derived
            ScopeModel("S", handlers = listOf(writes), derived = listOf(DerivedModel(list, listOf(list))))
        }
    }

    @Test
    fun examplesMustFitTheHandlersKind() {
        val state = ArgDef.Primitive("n", "Int")
        val one = kotlinx.serialization.json.JsonPrimitive(1)
        assertFailsWith<IllegalArgumentException> { // state handler without expect
            HandlerModel(EventModel("A"), HandlerOutput.Data(state), examples = listOf(ExampleModel(given = one)))
        }
        assertFailsWith<IllegalArgumentException> { // cascader without emits
            HandlerModel(EventModel("A"), HandlerOutput.Event(EventModel("B")), examples = listOf(ExampleModel()))
        }
        assertFailsWith<IllegalArgumentException> { // side effect: nothing to check
            HandlerModel(EventModel("A"), examples = listOf(ExampleModel()))
        }
        assertFailsWith<IllegalArgumentException> { // reads a container the handler doesn't read
            HandlerModel(EventModel("A"), HandlerOutput.Event(EventModel("B")), examples = listOf(ExampleModel(reads = mapOf("other" to one), emits = emptyList())))
        }
        HandlerModel(EventModel("A"), HandlerOutput.Data(state), examples = listOf(ExampleModel(given = one, expect = one)))
    }

    @Test
    fun genericAndCreatedCarryFieldNames() {
        val decoded = ScopeSpecFormat.decode("""[{ "name": "S", "handlers": [{ "input": { "name": "A", "args": [
            { "kind": "generics", "baseClassName": "List", "field": "tags", "parameters": [{ "kind": "primitive", "value": "t", "typeKey": "String" }] }
        ] } }] }]""").single()

        assertEquals("tags", decoded.handlers.single().input.args.single().fieldName)
    }
}
