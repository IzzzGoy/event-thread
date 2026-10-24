package ru.alexey.event.threads.codegen.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON Schema (2020-12) of the schema file - derived from the very serializers that read it, so
 * it can't describe a format the plugin doesn't parse. For editors (completion, inline errors)
 * and for AI agents writing schemas. Both the versioned object and the 1.1 bare array validate.
 */
@OptIn(ExperimentalSerializationApi::class)
object ScopeJsonSchema {

    fun generate(): String {
        val defs = linkedMapOf<String, JsonElement>()
        val document = ref(ScopeDocument.serializer().descriptor, defs)
        val scope = ref(ScopeModel.serializer().descriptor, defs)
        val root = buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("\$id", ScopeFormat.SCHEMA_URL)
            put("title", "event-thread scopes (format ${ScopeFormat.CURRENT})")
            put("oneOf", buildJsonArray {
                add(document)
                add(buildJsonObject { put("type", "array"); put("items", scope) })
            })
            put("\$defs", JsonObject(defs.toSortedMap()))
        }
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), root) + "\n"
    }

    private fun name(d: SerialDescriptor) = d.serialName.removeSuffix("?").substringAfterLast('.')

    /** Classes and sealed hierarchies go to `$defs` (they recurse - ArgDef holds ArgDefs). */
    private fun ref(d: SerialDescriptor, defs: MutableMap<String, JsonElement>): JsonElement {
        val kind = d.kind
        if (d.serialName.removeSuffix("?") == JSON_ELEMENT) return JsonObject(emptyMap())
        if (kind !is StructureKind.CLASS && kind !is PolymorphicKind.SEALED) return inline(d, defs)
        val key = name(d)
        if (key !in defs) {
            defs[key] = JsonObject(emptyMap()) // placeholder: breaks recursion
            defs[key] = if (kind is PolymorphicKind.SEALED) sealed(d, defs) else objectSchema(d, defs, null)
        }
        return buildJsonObject { put("\$ref", "#/\$defs/$key") }
    }

    private fun inline(d: SerialDescriptor, defs: MutableMap<String, JsonElement>): JsonElement = when (val kind: SerialKind = d.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> type("string")
        PrimitiveKind.BOOLEAN -> type("boolean")
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> type("integer")
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")
        SerialKind.ENUM -> buildJsonObject { put("enum", JsonArray(d.elementNames.map(::JsonPrimitive))) }
        StructureKind.LIST -> buildJsonObject { put("type", "array"); put("items", ref(d.getElementDescriptor(0), defs)) }
        StructureKind.MAP -> buildJsonObject { put("type", "object"); put("additionalProperties", ref(d.getElementDescriptor(1), defs)) }
        StructureKind.OBJECT -> buildJsonObject { put("type", "object") }
        else -> error("No JSON Schema mapping for $kind (${d.serialName})")
    }

    private fun sealed(d: SerialDescriptor, defs: MutableMap<String, JsonElement>): JsonElement {
        // Element 1 of a sealed descriptor holds one descriptor per subclass; the discriminator
        // (ScopeSpecFormat's `kind`) carries each one's serial name.
        val subclasses = d.getElementDescriptor(1).elementDescriptors.toList()
        return buildJsonObject {
            put("oneOf", JsonArray(subclasses.map { objectSchema(it, defs, it.serialName) }))
        }
    }

    private fun objectSchema(d: SerialDescriptor, defs: MutableMap<String, JsonElement>, kind: String?): JsonElement {
        val properties = linkedMapOf<String, JsonElement>()
        val required = mutableListOf<String>()
        kind?.let { properties[ScopeSpecFormat.json.configuration.classDiscriminator] = buildJsonObject { put("const", it) }; required += ScopeSpecFormat.json.configuration.classDiscriminator }
        for (i in 0 until d.elementsCount) {
            val element = d.getElementDescriptor(i)
            val schema = ref(element, defs)
            properties[d.getElementName(i)] = if (element.isNullable) {
                buildJsonObject { put("anyOf", buildJsonArray { add(schema); add(type("null")) }) }
            } else {
                schema
            }
            if (!d.isElementOptional(i)) required += d.getElementName(i)
        }
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
    }

    private fun type(name: String) = buildJsonObject { put("type", name) }

    private const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"
}
