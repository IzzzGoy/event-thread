package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.EventModel

/**
 * Maps spec [ArgDef]s/[EventModel]s onto Kotlin types:
 * - a qualified name (`kotlinx.datetime.Instant`, `org.company.sample.AddTodo`) is used as-is;
 * - a built-in simple name (`String`, `List`, ...) resolves to `kotlin`/`kotlin.collections`;
 * - any other simple name, and every [ArgDef.Created] and defined event, resolves in [packageName]
 *   (the `.models` package).
 *
 * An [ArgDef.Created] *with* fields is a definition - [dataClass] generates it; one without fields
 * is a reference to a type defined elsewhere (the spec repeats `{ "kind": "created", "name": "Todo" }`
 * rather than Todo's whole shape), so nothing is generated for it.
 */
internal class TypeResolver(private val packageName: String) {

    fun typeOf(arg: ArgDef): TypeName = when (arg) {
        is ArgDef.Primitive -> named(arg.typeKey)
        is ArgDef.Generics -> named(arg.baseClassName).parameterizedBy(arg.parameters.map(::typeOf))
        is ArgDef.Created -> ClassName(packageName, arg.name)
    }

    fun eventType(event: EventModel): ClassName =
        if (event.isReference) ClassName.bestGuess(event.name) else ClassName(packageName, event.name)

    /** `data class Name(val field: Type, ...)` - structural equality, which is what makes a value
     * usable as scope params (two loads with equal params are the same scope instance). */
    fun dataClass(created: ArgDef.Created): TypeSpec {
        require(created.fields.isNotEmpty()) { "'${created.name}' has no fields - nothing to generate" }
        return dataClass(created.name, created.fields).build()
    }

    /** A defined event: `data class Name(args) : Event`, or `data object Name : Event` without args. */
    fun eventClass(event: EventModel): TypeSpec {
        val builder = if (event.args.isEmpty()) {
            TypeSpec.objectBuilder(event.name).addModifiers(KModifier.DATA)
        } else {
            dataClass(event.name, event.args)
        }
        return builder.addSuperinterface(CoreSymbols.Event).build()
    }

    private fun dataClass(name: String, fields: List<ArgDef>): TypeSpec.Builder {
        val properties = fields.mapIndexed { index, field ->
            val property = requireNotNull(field.fieldName) {
                "'$name' field #$index (${field::class.simpleName}) has no name - set its `field`"
            }
            PropertySpec.builder(property, typeOf(field)).initializer(property).build()
        }
        return TypeSpec.classBuilder(name)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .apply { properties.forEach { addParameter(it.name, it.type) } }
                    .build()
            )
            .addProperties(properties)
    }

    private fun named(name: String): ClassName = when {
        '.' in name -> ClassName.bestGuess(name)
        name in KOTLIN -> ClassName("kotlin", name)
        name in COLLECTIONS -> ClassName("kotlin.collections", name)
        name == "RemoteData" -> CoreSymbols.RemoteData
        else -> ClassName(packageName, name)
    }

    companion object {
        private val KOTLIN = setOf("String", "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char", "Unit", "Any")
        private val COLLECTIONS = setOf("List", "Set", "Map", "Collection", "Iterable", "MutableList", "MutableSet", "MutableMap")

        /**
         * A container's name: the state's field name if it has one (a primitive's `value`, a
         * generic's/created type's `field` - `todos` -> `Todos`), else derived from its type: a
         * created type's name, or for generics the parameters' names then the base's
         * (`List<todo>` -> `TodoList`).
         */
        fun containerName(state: ArgDef): String = state.fieldName?.replaceFirstChar(Char::uppercaseChar) ?: when (state) {
            is ArgDef.Primitive -> state.value.replaceFirstChar(Char::uppercaseChar)
            is ArgDef.Created -> state.name
            is ArgDef.Generics -> state.parameters.joinToString("") { containerName(it) } +
                state.baseClassName.substringAfterLast('.')
        }

        /** This type and every type nested in it. */
        fun ArgDef.walk(): Sequence<ArgDef> = sequence {
            yield(this@walk)
            when (val self = this@walk) {
                is ArgDef.Generics -> self.parameters.forEach { yieldAll(it.walk()) }
                is ArgDef.Created -> self.fields.forEach { yieldAll(it.walk()) }
                is ArgDef.Primitive -> Unit
            }
        }
    }
}
