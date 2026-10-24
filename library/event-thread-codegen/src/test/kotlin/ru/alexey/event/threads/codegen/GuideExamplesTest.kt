package ru.alexey.event.threads.codegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import ru.alexey.event.threads.codegen.generator.SchemaValidator
import ru.alexey.event.threads.codegen.generator.TypedScopeSources
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every complete schema document shown in the docs parses, validates and generates - so the
 * guide can't teach a schema the plugin rejects. Fragments (with `<placeholders>`) are skipped. */
class GuideExamplesTest {

    private fun documents(file: String): List<String> =
        Regex("```json\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(File(file).readText())
            .map { it.groupValues[1] }
            // Skeletons and fragments carry placeholders (`[ ... ]`, `<todos>`) - not documents.
            .filterNot { "..." in it || Regex("<[A-Za-z]").containsMatchIn(it) }
            .filter { block -> runCatching { (Json.parseToJsonElement(block) as JsonObject).containsKey("scopes") }.getOrDefault(false) }
            .toList()

    @Test
    fun guideDocumentsGenerate() {
        val docs = documents("SCHEMA_GUIDE.md") + documents("README.md")
        assertTrue(docs.isNotEmpty(), "no complete schema documents found in the docs")
        docs.forEach { text ->
            val document = ScopeSpecFormat.decodeDocument(text)
            SchemaValidator.validate(document.scopes, strict = true)
            TypedScopeSources.generate("com.example", document)
            TypedScopeSources.generateTests("com.example", document)
        }
    }

    /** Every rule RULES.md's cookbook and `text` examples show parses - the reference can't teach
     * a rule the plugin rejects. */
    @Test
    fun rulesReferenceParses() {
        val doc = File("RULES.md").readText()
        val cookbook = doc.substringAfter("## Cookbook").substringBefore("\n## ")
        val fromTable = Regex("^\\| [^|]+ \\| `(.+)` \\|$", RegexOption.MULTILINE).findAll(cookbook)
            .flatMap { row -> row.groupValues[1].split("`, `") }
            .map { it.replace("\\|", "|").removeSurrounding("\"") }
            .toList()
        val fromBlocks = Regex("```text\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(doc)
            .map { it.groupValues[1] }
            // Blocks showing error output or the grammar aren't rules.
            .filterNot { "problems:" in it || ":=" in it }
            .flatMap { it.lines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() && "[" !in it.take(1) && !it.startsWith("\"") && ":=" !in it && "<" !in it }
            .toList()
        val rules = fromTable + fromBlocks
        assertTrue(rules.size > 20, "found only $rules")
        rules.forEach { rule -> ru.alexey.event.threads.codegen.rules.RuleParser.parse(rule) }
    }
}
