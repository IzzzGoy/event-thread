package ru.alexey.event.threads.codegen.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration scope specs are read/written with - also how the spec reaches
 * [ru.alexey.event.threads.codegen.GenerateTypedScopesTask] as a fingerprintable `@Input`.
 *
 * ```json
 * [{ "name": "Todo", "handlers": [{
 *     "input": { "name": "AddTodo", "args": [{ "kind": "primitive", "value": "title", "typeKey": "String" }] },
 *     "output": { "kind": "event", "event": { "name": "TodoAdded" } }
 * }] }]
 * ```
 */
object ScopeSpecFormat {
    /** `kind` rather than the default `type`, which [HandlerOutput.Data.type] already uses. */
    val json: Json = Json {
        classDiscriminator = "kind"
        prettyPrint = true
    }

    private val serializer = ListSerializer(ScopeModel.serializer())

    fun encode(scopes: List<ScopeModel>): String = json.encodeToString(serializer, scopes)

    fun decode(spec: String): List<ScopeModel> = decodeDocument(spec).scopes

    /** A schema file: the versioned object form, or the 1.1 bare array (format 1.0, no version). */
    fun decodeDocument(spec: String): ScopeDocument =
        if (spec.trimStart().startsWith("[")) {
            ScopeDocument(scopes = json.decodeFromString(serializer, spec))
        } else {
            json.decodeFromString(ScopeDocument.serializer(), spec)
        }

    fun encodeDocument(document: ScopeDocument): String = json.encodeToString(ScopeDocument.serializer(), document)
}
