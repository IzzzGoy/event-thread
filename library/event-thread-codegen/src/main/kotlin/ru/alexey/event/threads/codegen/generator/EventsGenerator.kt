package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import ru.alexey.event.threads.OutputMismatchBehavior
import ru.alexey.event.threads.Privacy
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel
import ru.alexey.event.threads.codegen.rules.CompiledRule
import kotlin.reflect.KClass

/**
 * The `.events` package: abstract handler bases (`<Event>StateProcessor`/`EventCascader`/
 * `SideEffect`), one `<Event>Handler.kt` per input event holding every base for it - e.g. one
 * scope's `AddTodoStateProcessor` next to another's `AddTodoEventCascader`. Each base's nested
 * `Deps` holds its own container and a `ContainerSnapshot` per `reads` entry; implementations
 * extend it and are bound in the scope's `DependencyProvider`.
 */
class EventsGenerator(private val packageName: String) {

    fun generate(scopes: List<ScopeModel>): List<FileSpec> = generate(GeneratorContext(packageName, scopes))

    internal fun generate(context: GeneratorContext): List<FileSpec> =
        context.handlers.groupBy { it.event.simpleName }.map { (event, handlers) ->
            FileSpec.builder(context.packages.events, "${event}Handler")
                .generatedBy(context)
                .apply {
                    handlers.forEach { handler ->
                        addType(base(context, handler))
                        context.rules[handler]?.let { addType(rule(context, handler, it)) }
                    }
                }
                .build()
        }

    /**
     * `<Name>Rule`: the schema rule compiled into an implementation of the base - the bindings'
     * default. Its KDoc is the rule as written, so a Kotlin type error in it points back to the
     * schema.
     */
    private fun rule(context: GeneratorContext, handler: GeneratorContext.Handler, compiled: CompiledRule): TypeSpec {
        val hasDeps = handler.ownState != null || handler.reads.isNotEmpty()
        val params = context.ruleParams(handler)
        val constructor = FunSpec.constructorBuilder()
        if (hasDeps) constructor.addParameter("deps", handler.className.nestedClass("Deps"))
        params?.let { constructor.addParameter("params", it) }
        val builder = TypeSpec.classBuilder(handler.ruleClassName)
            .addKdoc("Generated from the schema rule of [%N]:\n```\n%L\n```", handler.className.simpleName, handler.rule!!)
            .primaryConstructor(constructor.build())
            .superclass(handler.className)
        if (hasDeps) builder.addSuperclassConstructorParameter("deps")
        params?.let { builder.addProperty(PropertySpec.builder("params", it, KModifier.PRIVATE).initializer("params").build()) }
        val function = when (val output = handler.output) {
            is HandlerOutput.Data -> FunSpec.builder("process")
                .addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE, KModifier.SUSPEND)
                .addParameter("event", handler.event)
                .addParameter("state", context.container(output.type).state)
                .returns(context.container(output.type).state)
            is HandlerOutput.Event -> FunSpec.builder("cascade")
                .addModifiers(KModifier.PROTECTED, KModifier.OVERRIDE)
                .addParameter("input", handler.event)
                .returns(LIST.parameterizedBy(CoreSymbols.Event))
            HandlerOutput.None -> error("A side effect has no rule")
        }
        return builder.addFunction(function.addCode(compiled.body).build()).build()
    }

    private fun base(context: GeneratorContext, handler: GeneratorContext.Handler): TypeSpec {
        val own = handler.ownState?.let(context::container)
        val reads = handler.reads.map(context::container)
        val snapshot = { c: GeneratorContext.Container -> CoreSymbols.ContainerSnapshot.parameterizedBy(c.className, c.state) }
        val deps: List<Pair<String, TypeName>> =
            listOfNotNull(own?.let { it.property to it.className }) + reads.map { it.property to snapshot(it) }

        val builder = TypeSpec.classBuilder(handler.className).addModifiers(KModifier.ABSTRACT)
        if (deps.isNotEmpty()) {
            builder.primaryConstructor(
                FunSpec.constructorBuilder().addParameter("deps", handler.className.nestedClass("Deps")).build()
            )
            builder.addType(
                TypeSpec.classBuilder("Deps")
                    .primaryConstructor(FunSpec.constructorBuilder().apply { deps.forEach { (n, t) -> addParameter(n, t) } }.build())
                    .addProperties(deps.map { (n, t) -> PropertySpec.builder(n, t).initializer(n).build() })
                    .build()
            )
        }
        // Snapshots are what the implementation reads; its own container it only reaches through
        // `process(event, state)`.
        reads.forEach {
            builder.addProperty(
                PropertySpec.builder(it.property, snapshot(it), KModifier.PROTECTED).initializer("deps.%N", it.property).build()
            )
        }

        when (val output = handler.output) {
            is HandlerOutput.Data -> {
                builder.superclass(CoreSymbols.StateProcessor.parameterizedBy(handler.event, own!!.state))
                builder.addSuperclassConstructorParameter("deps.%N", own.property)
            }
            is HandlerOutput.Event -> {
                builder.superclass(CoreSymbols.EventCascader.parameterizedBy(handler.event))
                val outputsType = SET.parameterizedBy(
                    KClass::class.asClassName().parameterizedBy(WildcardTypeName.producerOf(CoreSymbols.Event))
                )
                builder.addProperty(
                    PropertySpec.builder("outputs", outputsType, KModifier.FINAL, KModifier.OVERRIDE)
                        .initializer("setOf(%T::class)", context.types.eventType(output.event))
                        .build()
                )
                if (output.onMismatch != OutputMismatchBehavior.Strict) {
                    builder.addProperty(
                        PropertySpec.builder("onMismatch", CoreSymbols.OutputMismatchBehavior, KModifier.OVERRIDE)
                            .initializer("%T.%L", CoreSymbols.OutputMismatchBehavior, output.onMismatch.name)
                            .build()
                    )
                }
            }
            HandlerOutput.None -> builder.superclass(CoreSymbols.SideEffect.parameterizedBy(handler.event))
        }
        if (handler.privacy != Privacy.public) {
            builder.addProperty(
                PropertySpec.builder("privacy", CoreSymbols.Privacy, KModifier.OVERRIDE)
                    .initializer("%T.%L", CoreSymbols.Privacy, handler.privacy.name)
                    .build()
            )
        }
        if (handler.override) {
            builder.addProperty(PropertySpec.builder("override", Boolean::class, KModifier.OVERRIDE).initializer("true").build())
        }
        return builder.build()
    }
}
