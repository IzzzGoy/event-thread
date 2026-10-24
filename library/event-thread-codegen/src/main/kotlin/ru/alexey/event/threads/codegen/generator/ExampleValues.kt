package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.EventModel

/**
 * Example JSON -> Kotlin expressions, at generation time - so example values needn't be
 * `@Serializable`, only constructible:
 * - typed wherever the schema knows the type: builtins become literals, `List`/`Set`/`Map` become
 *   `listOf`/`setOf`/`mapOf`, a `created` type (or a defined event) a call with its declared fields;
 * - an existing class (qualified name) from a JSON object: `Type(key = value, ...)` - its
 *   constructor parameter names must match the keys; from a JSON string: the enum entry `Type.VALUE`;
 * - nested values of an existing class are untyped: literals, lists - not objects (its field types
 *   aren't in the schema).
 */
internal class ExampleValues(private val types: TypeResolver) {

    fun of(type: ArgDef?, json: JsonElement, where: String): CodeBlock {
        if (json is JsonNull) return CodeBlock.of("null")
        return when (type) {
            null -> untyped(json, where)
            is ArgDef.Primitive -> primitive(type.typeKey, json, where)
            is ArgDef.Generics -> generics(type, json, where)
            is ArgDef.Created -> if (type.fields.isEmpty()) {
                construct(types.typeOf(type) as ClassName, json, where)
            } else {
                typedCall(types.typeOf(type) as ClassName, type.fields, json, where)
            }
        }
    }

    /** An event value: a defined event typed by its args; an existing one by its JSON keys; an
     * `object` event when [json] is absent. */
    fun event(event: EventModel, json: JsonElement?, where: String): CodeBlock {
        val type = types.eventType(event)
        return when {
            json == null -> CodeBlock.of("%T", type)
            !event.isReference -> if (event.args.isEmpty()) CodeBlock.of("%T", type) else typedCall(type, event.args, json, where)
            else -> construct(type, json, where)
        }
    }

    private fun primitive(typeKey: String, json: JsonElement, where: String): CodeBlock {
        val builtin = typeKey.removePrefix("kotlin.") in BUILTINS
        // An existing class's value is an object - check before treating it as a literal.
        if (!builtin && json is JsonObject) return construct(types.typeOf(ArgDef.Primitive("x", typeKey)) as ClassName, json, where)
        val p = json as? JsonPrimitive ?: fail(where, "$typeKey from ${json::class.simpleName}")
        return when (typeKey.removePrefix("kotlin.")) {
            "String" -> CodeBlock.of("%S", p.content)
            "Char" -> CodeBlock.of("'%L'", p.content.singleOrNull() ?: fail(where, "a Char from \"${p.content}\""))
            "Boolean", "Int", "Long", "Short", "Byte", "Double" -> CodeBlock.of("%L", p.content)
            "Float" -> CodeBlock.of("%Lf", p.content)
            else -> if (p.isString) {
                CodeBlock.of("%T.%L", types.typeOf(ArgDef.Primitive("x", typeKey)), p.content)
            } else {
                fail(where, "$typeKey from ${p.content}")
            }
        }
    }

    private fun generics(type: ArgDef.Generics, json: JsonElement, where: String): CodeBlock {
        val base = type.baseClassName.substringAfterLast('.')
        return when (base) {
            "List", "MutableList", "Collection", "Iterable", "Set", "MutableSet" -> {
                val items = (json as? JsonArray ?: fail(where, "$base from a non-array")).map { of(type.parameters.first(), it, where) }
                val factory = when (base) { "Set" -> "setOf"; "MutableSet" -> "mutableSetOf"; "MutableList" -> "mutableListOf"; else -> "listOf" }
                CodeBlock.of("%L<%T>(%L)", factory, types.typeOf(type.parameters.first()), items.joinToCode(", "))
            }
            "Map", "MutableMap" -> {
                val (keyType, valueType) = type.parameters.let { it.first() to it.getOrNull(1) }
                val entries = (json as? JsonObject ?: fail(where, "$base from a non-object")).map { (k, v) ->
                    CodeBlock.of("%L to %L", of(keyType, JsonPrimitive(k), where), of(valueType, v, where))
                }
                CodeBlock.of("%L<%T, %T>(%L)", if (base == "Map") "mapOf" else "mutableMapOf",
                    types.typeOf(keyType), types.typeOf(valueType ?: keyType), entries.joinToCode(", "))
            }
            else -> fail(where, "$base values - only List/Set/Map are supported in examples")
        }
    }

    private fun typedCall(type: ClassName, fields: List<ArgDef>, json: JsonElement, where: String): CodeBlock {
        val obj = json as? JsonObject ?: fail(where, "${type.simpleName} from a non-object")
        val byName = fields.associateBy { it.fieldName }
        val unknown = obj.keys - byName.keys.filterNotNull().toSet()
        if (unknown.isNotEmpty()) fail(where, "${type.simpleName} has no fields $unknown")
        val args = obj.map { (k, v) -> CodeBlock.of("%N = %L", k, of(byName.getValue(k), v, "$where.$k")) }
        return CodeBlock.of("%T(%L)", type, args.joinToCode(", "))
    }

    private fun construct(type: ClassName, json: JsonElement, where: String): CodeBlock {
        val obj = json as? JsonObject ?: fail(where, "${type.simpleName} from a non-object")
        val args = obj.map { (k, v) -> CodeBlock.of("%N = %L", k, untyped(v, "$where.$k")) }
        return CodeBlock.of("%T(%L)", type, args.joinToCode(", "))
    }

    private fun untyped(json: JsonElement, where: String): CodeBlock = when (json) {
        is JsonNull -> CodeBlock.of("null")
        is JsonPrimitive -> if (json.isString) CodeBlock.of("%S", json.content) else CodeBlock.of("%L", json.content)
        is JsonArray -> CodeBlock.of("listOf(%L)", json.map { untyped(it, where) }.joinToCode(", "))
        is JsonObject -> fail(where, "an object whose type isn't in the schema - describe it as a `created` type")
    }

    private companion object {
        val BUILTINS = setOf("String", "Char", "Boolean", "Int", "Long", "Short", "Byte", "Double", "Float")
    }

    private fun fail(where: String, what: String): Nothing = throw IllegalArgumentException("Example $where: can't build $what")
}
