package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import ru.alexey.event.threads.codegen.generator.TypeResolver.Companion.walk
import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.EventModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel

/**
 * The `.models` package: every event (simple name) and [ArgDef.Created] type (with fields) the
 * specs *define* - references to existing ones are just used. A name may be defined in several
 * places only identically; otherwise which shape wins would depend on order.
 */
class ModelsGenerator(private val packageName: String) {

    fun generate(scopes: List<ScopeModel>): List<FileSpec> = generate(GeneratorContext(packageName, scopes))

    internal fun generate(context: GeneratorContext): List<FileSpec> {
        val scopes = context.scopes
        val events = scopes.flatMap { scope ->
            scope.handlers.flatMap { listOf(it.input) + it.output.cascades }
        }.filterNot(EventModel::isReference)

        val argTrees = scopes.flatMap { scope ->
            listOfNotNull(scope.params) +
                scope.handlers.flatMap { h -> listOfNotNull(h.output.writes) + h.reads } +
                scope.derived.flatMap { listOf(it.state) + it.from } +
                events.flatMap { it.args }
        } + context.remote.flatMap { api -> api.endpoints.flatMap { it.args + listOfNotNull(it.response) } }
        val created = argTrees.flatMap { it.walk().toList() }
            .filterIsInstance<ArgDef.Created>()
            .filter { it.fields.isNotEmpty() }

        val eventFiles = events.groupBy { it.name }.map { (name, defs) ->
            require(defs.distinct().size == 1) { "Event '$name' is defined differently in different places" }
            file(context, name, context.types.eventClass(defs.first()))
        }
        // Types a remote API sends or receives - and the types inside them - are serialized.
        val definitions = created.associateBy { it.name }
        val serializable = context.remote
            .flatMap { api -> api.endpoints.flatMap { it.args + listOfNotNull(it.response) } }
            .flatMap { it.walk().toList() }.filterIsInstance<ArgDef.Created>().map { it.name }
            .toMutableSet()
        do {
            val nested = serializable.mapNotNull(definitions::get)
                .flatMap { it.fields.flatMap { f -> f.walk().toList() } }.filterIsInstance<ArgDef.Created>().map { it.name }
        } while (serializable.addAll(nested))

        val typeFiles = created.groupBy { it.name }.map { (name, defs) ->
            require(defs.distinct().size == 1) { "Type '$name' is defined differently in different places" }
            val type = context.types.dataClass(defs.first())
            file(context, name, if (name in serializable) type.toBuilder().addAnnotation(SERIALIZABLE).build() else type)
        }
        return eventFiles + typeFiles
    }

    private companion object {
        /** By name: generated code needs it only with a remote API, whose consumer applies the
         * kotlinx.serialization plugin anyway (for the client's JSON). */
        val SERIALIZABLE = com.squareup.kotlinpoet.ClassName("kotlinx.serialization", "Serializable")
    }

    private fun file(context: GeneratorContext, name: String, type: TypeSpec): FileSpec =
        FileSpec.builder(context.packages.models, name).generatedBy(context).addType(type).build()
}
