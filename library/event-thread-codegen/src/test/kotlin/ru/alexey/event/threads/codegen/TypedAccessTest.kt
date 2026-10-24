package ru.alexey.event.threads.codegen

import ru.alexey.event.threads.codegen.generator.SchemaCompatibility
import ru.alexey.event.threads.codegen.generator.SchemaValidator
import ru.alexey.event.threads.codegen.generator.ScopeReports
import ru.alexey.event.threads.codegen.generator.TypedScopeSources
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Typed container properties, `send`, Compose entry points, container `initial`s. */
class TypedAccessTest {

    private val flag = """{ "kind": "primitive", "value": "flag", "typeKey": "Boolean" }"""
    private val title = """{ "kind": "primitive", "value": "title", "typeKey": "String" }"""
    private val setFlag = """{ "name": "SetFlag", "args": [ { "kind": "primitive", "value": "on", "typeKey": "Boolean" } ] }"""

    private fun document(
        initial: String = "",
        params: Boolean = false,
        rule: String? = null,
        format: String = "1.1",
        derived: Boolean = false,
    ) = """{ "format": "$format", "scopes": [ { "name": "Panel"${if (params) """, "params": { "kind": "created", "name": "PanelParams", "fields": [ $title ] }""" else ""}
        ${if (initial.isNotEmpty()) """, "initial": { $initial }""" else ""}
        ${if (derived) """, "derived": [ { "state": { "kind": "primitive", "value": "shown", "typeKey": "Boolean" }, "from": [ $flag ] } ]""" else ""},
        "handlers": [
          { "input": $setFlag, "output": { "kind": "data", "type": $flag }${rule?.let { """, "rule": "$it"""" } ?: ""} },
          { "input": { "name": "SetTitle", "args": [ $title ] }, "output": { "kind": "data", "type": $title } },
          { "input": { "name": "Ping" } }
        ] } ] }"""

    private fun generate(doc: String, compose: Boolean = false) =
        TypedScopeSources.generate("com.example", ScopeSpecFormat.decodeDocument(doc), compose = compose).associate { it.name to it.toString() }

    @Test
    fun scopeExposesTypedContainersAndSenders() {
        val scope = generate(document()).getValue("PanelScope")
        assertContains(scope, "public val flag: FlagContainer\n    get() = typedContainer(\"flag\")")
        assertContains(scope, "public val title: TitleContainer\n    get() = typedContainer(\"title\")")
        assertContains(scope, "public fun send(event: SetFlag) {\n    plus(event)\n  }")
        assertContains(scope, "public fun send(event: Ping) {")
    }

    @Test
    fun containerNamesCantShadowScopeMembers() {
        val doc = document().replace("\"value\": \"title\"", "\"value\": \"params\"")
        assertContains(assertFailsWith<IllegalArgumentException> { generate(doc) }.message!!, "container 'params' would hide the scope's own member")
    }

    @Test
    fun composeEntryPointsOnlyWhenAskedFor() {
        assertFalse("PanelScopeContent" in generate(document()))
        val withParams = generate(document(params = true), compose = true).getValue("PanelScopeContent")
        assertContains(withParams, "@Composable\npublic fun PanelScopeContent(\n  params: PanelParams,\n  scopeHolder: ScopeHolder? = null,\n  content: @Composable PanelScope.() -> Unit,\n)")
        assertContains(withParams, "typedScope(PanelScopeKey, params, scopeHolder, { it as? PanelScope }, content)")
        assertContains(withParams, "public fun currentPanelScope(): PanelScope = checkNotNull(currentTypedScope { it as? PanelScope })")
        assertContains(withParams, "import androidx.compose.runtime.Composable")
        val noParams = generate(document(), compose = true).getValue("PanelScopeContent")
        assertContains(noParams, "typedScope(PanelScopeKey, Unit, scopeHolder, { it as? PanelScope }, content)")
    }

    @Test
    fun initialBecomesTheContainerDefault() {
        val files = generate(document(initial = """ "flag": "true", "title": "params.title + '!'" """, params = true))
        assertContains(files.getValue("FlagContainer"), "public open class FlagContainerInitial(\n  private val initial: Boolean,")
        assertContains(files.getValue("FlagContainer"), "protected override val source: ObservableResource<Boolean> = flowResource(initial)")
        val scope = files.getValue("PanelScope")
        assertContains(scope, "public fun flag(params: PanelParams): FlagContainer = FlagContainerInitial(true)")
        assertContains(scope, "public fun title(params: PanelParams): TitleContainer = TitleContainerInitial(params.title + \"!\")")
        assertContains(scope, "override fun flag(params: PanelParams): FlagContainer = resolveOrNull(FlagContainer::class, params) ?: super<PanelScopeBindings>.flag(params)")
        // No initial, no Initial class.
        assertFalse("Initial" in generate(document(initial = """ "flag": "true" """)).getValue("TitleContainer"))
    }

    @Test
    fun initialsAreChecked() {
        fun failure(doc: String) = assertFailsWith<IllegalArgumentException> { generate(doc) }.message!!
        assertContains(failure(document(initial = """ "flags": "true" """)), "initial flags: no such container - the scope's: flag, title")
        assertContains(failure(document(initial = """ "flag": "event.on" """)), "There's no event here")
        assertContains(failure(document(initial = """ "title": "params.title" """)), "This scope has no params")
        assertContains(failure(document(initial = """ "shown": "false" """, derived = true)), "a derived container's state comes from its transforms")
        assertContains(failure(document(initial = """ "flag": "emit Ping()" """)), "An initial value is an expression")
        assertContains(
            assertFailsWith<IllegalArgumentException> { ScopeSpecFormat.decodeDocument(document(initial = """ "flag": "true" """, format = "1.0")) }.message!!,
            "need \"format\": \"1.1\"",
        )
    }

    @Test
    fun singleQuotedStrings() {
        assertContains(generate(document(rule = "if event.on then 'on' == 'on' else false")).getValue("SetFlagHandler"), "if (event.on) \"on\" == \"on\" else false")
    }

    @Test
    fun longRulesWarn() {
        val long = "event.on" + " and event.on".repeat(12)
        val report = SchemaValidator.report(ScopeSpecFormat.decodeDocument(document(rule = long)).scopes)
        assertTrue(report.warnings.any { "rule is ${long.length} characters" in it }, report.warnings.toString())
        assertTrue(SchemaValidator.report(ScopeSpecFormat.decodeDocument(document(rule = "event.on")).scopes).warnings.isEmpty())
    }

    @Test
    fun initialInReportsAndVersioning() {
        val task = ScopeReports.tasks("com.example", ScopeSpecFormat.decodeDocument(document(initial = """ "flag": "true" """))).getValue("Panel.md")
        assertContains(task, "- [x] `flag` -> `FlagContainer` holding `kotlin.Boolean`: generated from `initial` `true` (`FlagContainerInitial`)")
        assertContains(task, "- [ ] `title`")
        fun scopes(initial: String) = ScopeSpecFormat.decodeDocument(document(initial = initial)).scopes
        fun levels(a: String, b: String) = SchemaCompatibility.diff(scopes(a), scopes(b)).map { it.level }
        assertEquals(listOf(SchemaCompatibility.Level.MINOR), levels("", """ "flag": "true" """))
        assertEquals(listOf(SchemaCompatibility.Level.PATCH), levels(""" "flag": "true" """, """ "flag": "false" """))
        assertEquals(listOf(SchemaCompatibility.Level.MAJOR), levels(""" "flag": "true" """, ""))
    }
}
