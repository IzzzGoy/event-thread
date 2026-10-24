package ru.alexey.event.threads.codegen.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A type reference in the scope spec, defined recursively: a [Generics] parameter or a [Created]
 * field is itself any [ArgDef], so arbitrarily nested shapes (`Map<String, List<Item>>`, a
 * `Created` type holding another `Created` type) are expressible. Serialized polymorphically -
 * the subtype is the `kind` discriminator (see [ScopeSpecFormat]).
 *
 * Where a type is a *named* thing - an event arg, a created type's field, a container - [fieldName]
 * names it: a [Primitive]'s `value`, a [Generics]'/[Created]'s optional `field`.
 */
@Serializable
sealed class ArgDef {

    /** The name of the arg/field/container this type describes, if it has one. */
    abstract val fieldName: String?

    /** A built-in/already-existing type: [typeKey] names it (`String`, `kotlin.Int`, ...);
     * [value] is the argument it describes. */
    @Serializable
    @SerialName("primitive")
    data class Primitive(val value: String, val typeKey: String) : ArgDef() {
        override val fieldName: String get() = value

        init {
            requireTypeName(typeKey, "Primitive type key")
        }
    }

    /** A parameterized type: [baseClassName]`<`[parameters]`>`, e.g.
     * `Generics("List", listOf(Primitive(..., "String")))` for `List<String>`. */
    @Serializable
    @SerialName("generics")
    data class Generics(
        val baseClassName: String,
        val parameters: List<ArgDef> = emptyList(),
        /** Name when used as an arg/field/container (`"todos"` for a `List<Todo>` field). */
        val field: String? = null,
    ) : ArgDef() {
        // `this.field`: a bare `field` in a getter is the backing-field keyword.
        override val fieldName: String? get() = this.field

        init {
            field?.let { requireIdentifier(it, "Generic field name") }
            requireTypeName(baseClassName, "Generic base class")
            require(parameters.isNotEmpty()) { "Generic '$baseClassName' needs at least one type parameter" }
        }
    }

    /** A type the generator creates: a class named [name] with one property per [fields] entry. */
    @Serializable
    @SerialName("created")
    data class Created(
        val name: String,
        val fields: List<ArgDef> = emptyList(),
        /** Name when used as an arg/field/container - [name] is the *type's* name. */
        val field: String? = null,
    ) : ArgDef() {
        // `this.field`: a bare `field` in a getter is the backing-field keyword.
        override val fieldName: String? get() = this.field

        init {
            field?.let { requireIdentifier(it, "Created field name") }
            requireIdentifier(name, "Created type name")
        }
    }
}
