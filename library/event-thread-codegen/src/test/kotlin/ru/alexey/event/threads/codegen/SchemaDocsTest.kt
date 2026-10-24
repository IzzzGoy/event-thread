package ru.alexey.event.threads.codegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import ru.alexey.event.threads.codegen.generator.ScopeReports
import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.EventModel
import ru.alexey.event.threads.codegen.model.HandlerModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeDocument
import ru.alexey.event.threads.codegen.model.ScopeJsonSchema
import ru.alexey.event.threads.codegen.model.ScopeModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.fail

class SchemaDocsTest {

    @Test
    fun jsonSchemaDescribesBothRootFormsAndTheKindDiscriminator() {
        val schema = Json.parseToJsonElement(ScopeJsonSchema.generate()).jsonObject

        assertEquals(2, schema.getValue("oneOf").jsonArray.size)
        val defs = schema.getValue("\$defs").jsonObject
        val argDef = defs.getValue("ArgDef").toString()
        assertContains(argDef, "\"const\":\"primitive\"")
        assertContains(argDef, "\"const\":\"generics\"")
        assertContains(defs.getValue("ScopeDocument").toString(), "\"\$schema\"")
        assertContains(defs.getValue("HandlerModel").toString(), "\"examples\"")
    }

    /** The JSON Schema editors and agents use (`$schema`) is committed next to this module -
     * it must match the format the plugin reads. Regenerate with UPDATE_SCOPES_SCHEMA=1. */
    @Test
    fun committedJsonSchemaIsUpToDate() {
        val committed = File("scopes.schema.json")
        val generated = ScopeJsonSchema.generate()
        if (System.getenv("UPDATE_SCOPES_SCHEMA") == "1") committed.writeText(generated)
        if (!committed.isFile || committed.readText() != generated) {
            fail("scopes.schema.json is out of date - run the codegen tests with UPDATE_SCOPES_SCHEMA=1 and commit it")
        }
    }

    private val count = ArgDef.Primitive("count", "Int")
    private val document = ScopeDocument(version = "1.0.0", scopes = listOf(
        ScopeModel("Tally", description = "Counts adds", dependsOn = listOf("Rules"), handlers = listOf(
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(count)),
        )),
        ScopeModel("Rules", consumes = listOf("com.example.Add"), handlers = listOf(
            HandlerModel(EventModel("com.example.Add"), HandlerOutput.Event(EventModel("com.example.Limit"))),
            HandlerModel(EventModel("com.example.Limit")),
        )),
    ))

    @Test
    fun diagramShowsHandlersCascadesAndRelations() {
        val diagram = ScopeReports.diagram(document)

        assertContains(diagram, "```mermaid\nflowchart LR")
        assertContains(diagram, "subgraph Tally[\"Tally\"]")
        assertContains(diagram, "Tally_com_example_Add --> Tally_c_Count")
        assertContains(diagram, "Rules_com_example_Add -- emits --> Rules_com_example_Limit")
        assertContains(diagram, "Tally == dependsOn ==> Rules")
    }

    @Test
    fun tasksListEveryBindingWithItsContext() {
        val task = ScopeReports.tasks("com.example", document).getValue("Tally.md")

        assertContains(task, "# Tally - implementation tasks")
        assertContains(task, "Implement `TallyScopeBindings`")
        assertContains(task, "- [ ] `count` -> `CountContainer` holding `kotlin.Int`. Supply `source` - its initial state.")
        assertContains(task, "- [ ] `addStateProcessor` -> `AddStateProcessor`: on `Add`, compute the new `count` (`kotlin.Int`) in `process(event, state)`.")
        assertContains(task, "Loading it also loads: `Rules`.")
        assertContains(task, "A test extending `TallyScopeContract` passes")
    }
}
