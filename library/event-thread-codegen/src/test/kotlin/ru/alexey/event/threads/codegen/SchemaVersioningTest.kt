package ru.alexey.event.threads.codegen

import ru.alexey.event.threads.codegen.generator.SchemaCompatibility
import ru.alexey.event.threads.codegen.generator.SchemaCompatibility.Level
import ru.alexey.event.threads.codegen.generator.SchemaValidator
import ru.alexey.event.threads.codegen.generator.TypedScopeSources
import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.EventModel
import ru.alexey.event.threads.codegen.model.HandlerModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeDocument
import ru.alexey.event.threads.codegen.model.ScopeFormat
import ru.alexey.event.threads.codegen.model.ScopeModel
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import ru.alexey.event.threads.codegen.model.SemVer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaVersioningTest {

    // --- document format ------------------------------------------------------------------

    @Test
    fun versionedDocumentAndLegacyArrayBothRead() {
        val doc = ScopeSpecFormat.decodeDocument("""{ "${'$'}schema": "x", "format": "1.0", "version": "2.1.0", "scopes": [{ "name": "A" }] }""")
        assertEquals("2.1.0", doc.version)
        assertEquals(listOf("A"), doc.scopes.map { it.name })

        val legacy = ScopeSpecFormat.decodeDocument("""[{ "name": "A" }]""")
        assertEquals(ScopeFormat.CURRENT, legacy.format)
        assertNull(legacy.version)
    }

    @Test
    fun newerOrIncompatibleFormatIsRejected() {
        val newer = assertFailsWith<IllegalArgumentException> { ScopeSpecFormat.decodeDocument("""{ "format": "1.9", "scopes": [] }""") }
        assertContains(newer.message.orEmpty(), "update event-thread-codegen")
        val major = assertFailsWith<IllegalArgumentException> { ScopeSpecFormat.decodeDocument("""{ "format": "2.0", "scopes": [] }""") }
        assertContains(major.message.orEmpty(), "incompatible")
        assertFailsWith<IllegalArgumentException> { ScopeSpecFormat.decodeDocument("""{ "version": "1.0", "scopes": [] }""") }
    }

    @Test
    fun semverOrdersReleasesAfterPrereleases() {
        assertTrue(SemVer.parse("1.2.0") > SemVer.parse("1.1.9"))
        assertTrue(SemVer.parse("1.2.0") > SemVer.parse("1.2.0-rc1"))
        assertTrue(SemVer.parse("1.2.0-rc2") > SemVer.parse("1.2.0-rc1"))
        assertEquals("1.2.0-rc1", SemVer.parse("1.2.0-rc1").toString())
    }

    // --- cycles -----------------------------------------------------------------------------

    private val ping = EventModel("com.example.Ping")
    private val pong = EventModel("com.example.Pong")

    @Test
    fun cascadeLoopAcrossScopesIsAWarningOrInStrictModeAnError() {
        val a = ScopeModel("A", consumes = listOf("com.example.Ping"), handlers = listOf(HandlerModel(ping, HandlerOutput.Event(pong))))
        val b = ScopeModel("B", consumes = listOf("com.example.Pong"), handlers = listOf(HandlerModel(pong, HandlerOutput.Event(ping))))

        val report = SchemaValidator.report(listOf(a, b))

        assertTrue(report.errors.isEmpty(), "${report.errors}")
        assertEquals(1, report.warnings.size, "${report.warnings}") // one loop, however it's entered
        assertContains(report.warnings.single(), "Cascade loop:")
        SchemaValidator.validate(listOf(a, b))
        assertFailsWith<IllegalArgumentException> { SchemaValidator.validate(listOf(a, b), strict = true) }
    }

    // --- compatibility ------------------------------------------------------------------------

    private val count = ArgDef.Primitive("count", "Int")
    private val add = HandlerModel(EventModel("com.example.Add"), HandlerOutput.Data(count))
    private val base = ScopeModel("Tally", description = "counts", handlers = listOf(add))

    private fun doc(version: String?, vararg scopes: ScopeModel) = ScopeDocument(version = version, scopes = scopes.toList())
    private fun levels(before: ScopeModel, after: ScopeModel) = SchemaCompatibility.diff(listOf(before), listOf(after)).map { it.level }.toSet()

    @Test
    fun changesAreClassified() {
        assertEquals(setOf(Level.PATCH), levels(base, base.copy(description = "tallies")))
        assertEquals(setOf(Level.MINOR), levels(base, base.copy(handlers = listOf(add, HandlerModel(EventModel("com.example.Reset"), HandlerOutput.Data(count))))))
        assertEquals(setOf(Level.MAJOR), levels(base, base.copy(handlers = emptyList())))
        assertEquals(setOf(Level.MAJOR), levels(base, base.copy(handlers = listOf(add.copy(output = HandlerOutput.Data(ArgDef.Primitive("count", "Long")))))))
        assertEquals(setOf(Level.MAJOR), levels(base, base.copy(params = ArgDef.Primitive("p", "String"))))
        assertEquals(setOf(Level.MINOR), levels(base, base.copy(dependsOn = listOf("Other"))))
        assertEquals(setOf(Level.MAJOR), levels(base.copy(dependsOn = listOf("Other")), base))
        assertEquals(emptySet(), levels(base, base))
    }

    @Test
    fun versionMustBeBumpedAsChangesRequire() {
        val more = base.copy(handlers = listOf(add, HandlerModel(EventModel("com.example.Reset"), HandlerOutput.Data(count))))

        assertNull(SchemaCompatibility.check(doc("1.0.0", base), doc("1.1.0", more)).problem)
        assertContains(SchemaCompatibility.check(doc("1.0.0", base), doc("1.0.1", more)).problem.orEmpty(), "minor version bump from 1.0.0 (at least 1.1.0)")
        assertContains(SchemaCompatibility.check(doc("1.0.0", base), doc("1.9.0", base.copy(handlers = emptyList()))).problem.orEmpty(), "at least 2.0.0")
        assertNull(SchemaCompatibility.check(doc("1.0.0", base), doc("1.0.0", base)).problem)
        assertContains(SchemaCompatibility.check(doc("1.2.0", base), doc("1.1.0", base)).problem.orEmpty(), "lower than the baseline")
        assertContains(SchemaCompatibility.check(doc("1.0.0", base), doc(null, more)).problem.orEmpty(), "has no `version`")
    }

    @Test
    fun zeroMajorFollowsSemverInitialDevelopment() {
        assertNull(SchemaCompatibility.check(doc("0.3.0", base), doc("0.4.0", base.copy(handlers = emptyList()))).problem)
        assertContains(SchemaCompatibility.check(doc("0.3.0", base), doc("0.3.1", base.copy(handlers = emptyList()))).problem.orEmpty(), "at least 0.4.0")
    }

    // --- stamps -----------------------------------------------------------------------------

    @Test
    fun generatedCodeIsStampedWithItsVersions() {
        val files = TypedScopeSources.generate("com.example", doc("3.1.4", base), generatorVersion = "1.2.0")

        assertContains(files.first { it.name == "TallyScope" }.toString(), "event-thread-codegen 1.2.0, schema 3.1.4, format ${ScopeFormat.CURRENT}")
        val info = files.first { it.name == "ScopesSchema" }.toString()
        assertContains(info, "public val VERSION: String? = \"3.1.4\"")
        assertContains(info, "public const val FORMAT: String = \"${ScopeFormat.CURRENT}\"")
    }
}
