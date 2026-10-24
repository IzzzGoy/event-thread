package ru.alexey.event.threads.codegen

import ru.alexey.event.threads.codegen.generator.SchemaCompatibility
import ru.alexey.event.threads.codegen.generator.ScopeReports
import ru.alexey.event.threads.codegen.generator.TypedScopeSources
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import ru.alexey.event.threads.codegen.rules.RuleException
import ru.alexey.event.threads.codegen.rules.RuleParser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleTest {

    private val todo = """{ "kind": "created", "name": "Todo", "field": "todo", "fields": [
        { "kind": "primitive", "value": "id", "typeKey": "Long" },
        { "kind": "primitive", "value": "text", "typeKey": "String" },
        { "kind": "primitive", "value": "done", "typeKey": "Boolean" } ] }"""
    private val todos = """{ "kind": "generics", "baseClassName": "List", "field": "todos", "parameters": [ { "kind": "created", "name": "Todo" } ] }"""
    private val edit = """{ "name": "Edit", "args": [ { "kind": "primitive", "value": "id", "typeKey": "Long" }, { "kind": "primitive", "value": "text", "typeKey": "String" } ] }"""
    private val count = """{ "kind": "primitive", "value": "count", "typeKey": "Int" }"""

    /** A Todo scope: list + count containers, one handler per call with [rule]. */
    private fun document(
        listRule: String? = null,
        countRule: String? = null,
        cascadeRule: String? = null,
        format: String = "1.1",
        params: Boolean = false,
    ): String {
        fun r(rule: String?) = rule?.let { """, "rule": ${quote(it)}""" } ?: ""
        return """{ "format": "$format", "scopes": [ { "name": "Todo"${if (params) """, "params": { "kind": "created", "name": "ListParams", "fields": [ { "kind": "primitive", "value": "limit", "typeKey": "Int" } ] }""" else ""},
          "handlers": [
            { "input": { "name": "Seed", "args": [ $todo ] }, "output": { "kind": "data", "type": $todos } },
            { "input": $edit,
              "output": { "kind": "data", "type": $todos }${r(listRule)} },
            { "input": $edit, "output": { "kind": "data", "type": $count }, "name": "CountEdits"${r(countRule)} },
            { "input": $edit, "output": { "kind": "event", "event": { "name": "Full", "args": [ $count ] } },
              "reads": [ $count ]${r(cascadeRule)} },
            { "input": { "name": "Full", "args": [ $count ] } }
          ] } ] }"""
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun generate(doc: String): Map<String, String> =
        TypedScopeSources.generate("com.example", ScopeSpecFormat.decodeDocument(doc)).associate { it.name to it.toString() }

    private fun events(doc: String) = generate(doc).getValue("EditHandler")

    private fun failure(doc: String): String =
        assertFailsWith<IllegalArgumentException> { generate(doc) }.message!!

    @Test
    fun stateRulesCompileToProcess() {
        val code = events(document(listRule = "state | update where it.id == event.id set (text: event.text)"))
        assertContains(code, "public class EditStateProcessorRule(")
        assertContains(code, "protected override suspend fun process(event: Edit, state: List<Todo>): List<Todo> = " +
            "state.map { if (it.id == event.id) it.copy(text = event.text) else it }")
    }

    @Test
    fun containerNameIsTheState() {
        val code = events(document(listRule = "todos | remove where it.id == event.id"))
        assertContains(code, "= state.filterNot { it.id == event.id }")
    }

    @Test
    fun cascadeRulesEmitConditionally() {
        val code = events(document(cascadeRule = "when count >= 3 emit Full(count); emit Full(count: 0)"))
        assertContains(code, "protected override fun cascade(input: Edit): List<Event> = buildList {")
        assertContains(code, "if (count.value >= 3) add(Full(count.value))")
        assertContains(code, "add(Full(count = 0))")
    }

    /** Generated Kotlin per rule, checked as text (types are the Kotlin compiler's business - the
     * sample app compiles real rules). */
    private fun assertCompiles(rule: String, kotlin: String) =
        assertContains(events(document(listRule = rule)), "= $kotlin\n", message = rule)

    @Test
    fun pipelineStages() {
        assertCompiles("state | where not it.done", "state.filter { !it.done }")
        assertCompiles("state | remove where it.done", "state.filterNot { it.done }")
        assertCompiles("state | select it with (done: true)", "state.map { it.copy(done = true) }")
        assertCompiles("state | update where it.done set (text: \"x\", done: false)", "state.map { if (it.done) it.copy(text = \"x\", done = false) else it }")
        assertCompiles("state | where not it.done | select it.text | count", "state.filter { !it.done }.map { it.text }.count()")
        assertCompiles("state | count it.done", "state.count { it.done }")
        assertCompiles("state | sum it.id", "state.sumOf { it.id }")
        assertCompiles("state | sum", "state.sum()")
        assertCompiles("state | first it.done", "state.firstOrNull { it.done }")
        assertCompiles("state | last", "state.lastOrNull()")
        assertCompiles("state | any it.done", "state.any { it.done }")
        assertCompiles("state | all it.done", "state.all { it.done }")
        assertCompiles("state | none it.done", "state.none { it.done }")
        assertCompiles("state | sortBy it.text", "state.sortedBy { it.text }")
        assertCompiles("state | sortByDescending it.id | take 3", "state.sortedByDescending { it.id }.take(3)")
        assertCompiles("state | sorted | distinct | drop 1", "state.sorted().distinct().drop(1)")
        // `?:` binds looser than `|`: the pipe applies to `event.text`.
        assertCompiles("(state | first it.done)?.text ?: event.text | count", "state.firstOrNull { it.done }?.text ?: event.text.count()")
        assertCompiles("state + Todo(id: event.id, text: event.text, done: false)", "state + Todo(id = event.id, text = event.text, done = false)")
        assertCompiles("state + [Todo(event.id, event.text, false)]", "state + listOf(Todo(event.id, event.text, false))")
        assertCompiles("(state + []) | where it.done", "(state + emptyList()).filter { it.done }")
        // The result of a pipeline in an expression needs parens - pipes bind loosest.
        assertCompiles("if (state | count) > 3 then state | take 3 else state", "if (state.count() > 3) state.take(3) else state")
        // Nested pipelines: the inner `it` is the inner element, as in Kotlin.
        assertCompiles("state | where (state | any it.text == \"x\")", "state.filter { state.any { it.text == \"x\" } }")
    }

    @Test
    fun precedenceIsPreserved() {
        fun count(rule: String, kotlin: String) = assertContains(events(document(countRule = rule)), "= $kotlin\n", message = rule)
        count("(state + 1) * 2", "(state + 1) * 2")
        count("state + 1 * 2", "state + 1 * 2")
        count("state - (1 - 2)", "state - (1 - 2)")
        count("- -state", "-(-state)")
        count("if not (state == 1) then 0 else 1", "if (!(state == 1)) 0 else 1")
        count("if state > 1 and state < 5 or state == 0 then 1 else -state", "if (state > 1 && state < 5 || state == 0) 1 else -state")
        // Comparison binds tighter than equality in both - no parens needed.
        count("if (state > 1) == true then 1 else 0", "if (state > 1 == true) 1 else 0")
        // Elvis binds tighter than comparison in Kotlin, looser here - parens keep the meaning.
        count("if (event.text == \"\") ?: false then 1 else 0", "if ((event.text == \"\") ?: false) 1 else 0")
        // Strings keep their escapes.
        count("if event.text == \"a\\\"b\" then 1 else 0", "if (event.text == \"a\\\"b\") 1 else 0")
    }

    @Test
    fun bindingsDefaultToTheRuleAndDiStillWins() {
        val scope = generate(document(listRule = "state | remove where it.id == event.id")).getValue("TodoScope")
        assertContains(scope, "public fun editStateProcessor(deps: EditStateProcessor.Deps): EditStateProcessor = EditStateProcessorRule(deps)")
        assertContains(scope, "resolveOrNull(EditStateProcessor::class, deps) ?: super<TodoScopeBindings>.editStateProcessor(deps)")
        // Handlers without a rule stay abstract - still the developer's to write.
        assertContains(scope, "public fun seedStateProcessor(deps: SeedStateProcessor.Deps): SeedStateProcessor\n")
        assertContains(scope, "= resolve(SeedStateProcessor::class, deps)")
    }

    @Test
    fun paramsReachTheRule() {
        val files = generate(document(countRule = "if state >= params.limit then state else state + 1", params = true))
        assertContains(files.getValue("EditHandler"), "private val params: ListParams")
        assertContains(files.getValue("EditHandler"), "if (state >= params.limit) state else state + 1")
        assertContains(files.getValue("TodoScope"), "= CountEditsRule(deps, params)")
        // A rule that doesn't use params doesn't get them.
        assertContains(generate(document(listRule = "state", params = true)).getValue("TodoScope"), "= EditStateProcessorRule(deps)")
    }

    @Test
    fun namesAreChecked() {
        assertContains(failure(document(listRule = "state | where it.id == evnt.id")), "Unknown name 'evnt' - this rule can use: event, state, todos")
        assertContains(failure(document(listRule = "state + it")), "`it` is only defined inside a pipeline stage")
        assertContains(failure(document(cascadeRule = "when state > 1 emit Full(1)")), "`state` is a state handler's own state")
        assertContains(failure(document(countRule = "params.limit")), "This scope has no params")
        assertContains(failure(document(listRule = "state + Todoo(id: 1)")), "Unknown type 'Todoo'")
        // Every bad rule is reported at once.
        val both = failure(document(listRule = "x", countRule = "y"))
        assertContains(both, "EditStateProcessor rule: Unknown name 'x'")
        assertContains(both, "CountEdits rule: Unknown name 'y'")
    }

    @Test
    fun shapeIsChecked() {
        assertContains(failure(document(listRule = "emit Full(1)")), "A state handler's rule is an expression")
        assertContains(failure(document(cascadeRule = "count + 1")), "A cascading handler's rule emits events")
        assertContains(failure(document(cascadeRule = "emit Edit(1, \"a\")")), "This handler's output is Full - it can't emit Edit")
        assertContains(failure(document(listRule = "state | select it with (1)")), "`with` takes named fields")
    }

    @Test
    fun ruleNeedsFormat11AndAnOutput() {
        assertContains(failure(document(listRule = "state", format = "1.0")), "Handler rules and initial values need \"format\": \"1.1\"")
        val sideEffect = """{ "format": "1.1", "scopes": [ { "name": "A", "handlers": [ { "input": { "name": "Ping" }, "rule": "event" } ] } ] }"""
        assertContains(assertFailsWith<IllegalArgumentException> { ScopeSpecFormat.decodeDocument(sideEffect) }.message!!, "a side effect can't have a rule")
    }

    @Test
    fun syntaxErrorsPointAtTheColumn() {
        val error = assertFailsWith<RuleException> { RuleParser.parse("state | where it.id ==") }
        assertEquals(22, error.at)
        assertContains(error.message!!, "Unexpected end of rule\n  state | where it.id ==\n                        ^")
        assertContains(assertFailsWith<RuleException> { RuleParser.parse("state | filter it") }.message!!, "Expected a pipeline stage")
        assertContains(assertFailsWith<RuleException> { RuleParser.parse("\"open") }.message!!, "Unterminated string")
        assertContains(assertFailsWith<RuleException> { RuleParser.parse("a # b") }.message!!, "Unexpected character '#'")
        // Decoding reports it with the handler it belongs to.
        assertContains(failure(document(listRule = "state |")), "Edit rule: Expected a pipeline stage")
    }

    @Test
    fun stageWordsAreNamesOutsidePipelines() {
        // `count` is a container here - a stage keyword only right after `|`.
        val code = events(document(cascadeRule = "when count > 0 emit Full(count)"))
        assertContains(code, "if (count.value > 0) add(Full(count.value))")
        assertFalse("count()" in code)
    }

    @Test
    fun tasksMarkRuledHandlersDone() {
        val task = ScopeReports.tasks("com.example", ScopeSpecFormat.decodeDocument(document(listRule = "state"))).getValue("Todo.md")
        assertContains(task, "- [x] `editStateProcessor` -> `EditStateProcessor`: generated from the rule `state` (`EditStateProcessorRule`)")
        assertContains(task, "- [ ] `countEdits`")
    }

    @Test
    fun ruleChangesAreVersioned() {
        fun scopes(rule: String?) = ScopeSpecFormat.decodeDocument(document(listRule = rule)).scopes
        fun levels(from: String?, to: String?) = SchemaCompatibility.diff(scopes(from), scopes(to)).map { it.level }
        assertEquals(listOf(SchemaCompatibility.Level.MINOR), levels(null, "state"))
        assertEquals(listOf(SchemaCompatibility.Level.PATCH), levels("state", "state + []"))
        assertEquals(listOf(SchemaCompatibility.Level.MAJOR), levels("state", null))
    }
}
