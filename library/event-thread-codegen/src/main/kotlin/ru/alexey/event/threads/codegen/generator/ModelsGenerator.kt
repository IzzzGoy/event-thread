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
            scope.handlers.flatMap { listOfNotNull(it.input, (it.output as? HandlerOutput.Event)?.event) }
        }.filterNot(EventModel::isReference)

        val argTrees = scopes.flatMap { scope ->
            listOfNotNull(scope.params) +
                scope.handlers.flatMap { h -> listOfNotNull((h.output as? HandlerOutput.Data)?.type) + h.reads } +
                scope.derived.flatMap { listOf(it.state) + it.from } +
                events.flatMap { it.args }
        }
        val created = argTrees.flatMap { it.walk().toList() }
            .filterIsInstance<ArgDef.Created>()
            .filter { it.fields.isNotEmpty() }

        val eventFiles = events.groupBy { it.name }.map { (name, defs) ->
            require(defs.distinct().size == 1) { "Event '$name' is defined differently in different places" }
            file(context, name, context.types.eventClass(defs.first()))
        }
        val typeFiles = created.groupBy { it.name }.map { (name, defs) ->
            require(defs.distinct().size == 1) { "Type '$name' is defined differently in different places" }
            file(context, name, context.types.dataClass(defs.first()))
        }
        return eventFiles + typeFiles
    }

    private fun file(context: GeneratorContext, name: String, type: TypeSpec) =
        FileSpec.builder(context.packages.models, name).addFileComment(GENERATED).addType(type).build()
}
