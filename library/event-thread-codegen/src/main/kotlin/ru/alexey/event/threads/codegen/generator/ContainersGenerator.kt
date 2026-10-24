package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import ru.alexey.event.threads.codegen.model.ScopeModel

/**
 * The `.containers` package: one abstract `<State>Container` per distinct state across all
 * scopes. Abstract because the spec can't say what a state starts as - the `DependencyProvider`
 * binding supplies `source`. A derived one also gets a `Deps` with the containers it's computed
 * from, exposed for the implementation's `transforms`.
 */
class ContainersGenerator(private val packageName: String) {

    fun generate(scopes: List<ScopeModel>): List<FileSpec> = generate(GeneratorContext(packageName, scopes))

    internal fun generate(context: GeneratorContext): List<FileSpec> = context.containers.values.map { container ->
        // `coroutineScope` passes through to DatacontainerTyped, so an implementation picks where its
        // pipeline runs (e.g. Dispatchers.Main for UI-bound state); same default as DatacontainerTyped.
        val coroutineScope = ParameterSpec.builder("coroutineScope", CoreSymbols.CoroutineScope)
            .defaultValue("%T(%T.Default)", CoreSymbols.CoroutineScope, CoreSymbols.Dispatchers)
            .build()
        val sources = container.from?.map(context::container)
        val type = TypeSpec.classBuilder(container.className)
            .addModifiers(KModifier.ABSTRACT)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .apply { if (sources != null) addParameter("deps", container.className.nestedClass("Deps")) }
                    .addParameter(coroutineScope)
                    .build()
            )
            .superclass(CoreSymbols.DatacontainerTyped.parameterizedBy(container.state))
            .addSuperclassConstructorParameter("coroutineScope")
        sources?.let { sources ->
            type.addType(
                TypeSpec.classBuilder("Deps")
                    .primaryConstructor(FunSpec.constructorBuilder().apply { sources.forEach { addParameter(it.property, it.className) } }.build())
                    .addProperties(sources.map { PropertySpec.builder(it.property, it.className).initializer(it.property).build() })
                    .build()
            )
            sources.forEach {
                type.addProperty(PropertySpec.builder(it.property, it.className, KModifier.PROTECTED).initializer("deps.%N", it.property).build())
            }
        }
        FileSpec.builder(container.className).generatedBy(context).addType(type.build()).build()
    }
}
