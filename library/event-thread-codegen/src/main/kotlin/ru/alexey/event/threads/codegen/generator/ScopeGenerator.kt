package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.joinToCode
import ru.alexey.event.threads.codegen.model.ScopeModel

/**
 * The `.scopes` package: per [ScopeModel], one file with
 * - `object <Name>ScopeKey : ScopeKey<P>` - typed identity, pinned to the spec name; `P` is the
 *   spec's `params` type (from `.models`), `Unit` without one;
 * - `class <Name>Scope(params, dependencyProvider) : ScopeTyped<P>` whose generated `wire()`
 *   builds the scope's containers (`.containers`) and handlers (`.events`) through its bindings;
 * - `<Name>ScopeBindings` - one factory per container and handler (compile-checked completeness),
 *   and `<Name>ScopeDependencyBindings` implementing it from a DependencyProvider;
 * - `<Name>ScopeFragment` - its relations (`dependsOn`, `consumes`) as a `ScopeKey.extend` fragment;
 * - `ScopeHolderBuilder.register<Name>Scope()` (from the DependencyProvider) and
 *   `register<Name>Scope(bindings)` - the typed factory plus that fragment, in one call.
 */
class ScopeGenerator(private val packageName: String) {

    fun generate(scopes: List<ScopeModel>): List<FileSpec> {
        val context = GeneratorContext(packageName, scopes)
        return scopes.map { generate(context, it) }
    }

    /**
     * One container or handler the scope needs - a member of its bindings interface. [deps]: the
     * `Deps` it's built from, if any; [build]: the `Deps(...)` construction for `wire()`.
     */
    private class Member(val name: String, val returns: ClassName, val deps: ClassName?, val build: CodeBlock?, val handles: ClassName?)

    private fun membersOf(context: GeneratorContext, scope: ScopeModel): List<Member> {
        val containers = context.containersOf(scope).map { c ->
            val from = c.from
            Member(
                name = c.property,
                returns = c.className,
                deps = from?.let { c.className.nestedClass("Deps") },
                build = from?.let { states ->
                    CodeBlock.of("%T(%L)", c.className.nestedClass("Deps"),
                        states.map { CodeBlock.of("%N", context.container(it).property) }.joinToCode(", "))
                },
                handles = null,
            )
        }
        val handlers = scope.handlers.map(context::handlerOf).distinct().map { h ->
            val args = listOfNotNull(h.ownState?.let { CodeBlock.of("%N", context.container(it).property) }) +
                h.reads.map { CodeBlock.of("%N.%M()", context.container(it).property, CoreSymbols.snapshot) }
            Member(
                name = h.className.simpleName.replaceFirstChar(Char::lowercaseChar),
                returns = h.className,
                deps = if (args.isEmpty()) null else h.className.nestedClass("Deps"),
                // joinToCode, not joinToString: a stringified CodeBlock loses its imports.
                build = if (args.isEmpty()) null else CodeBlock.of("%T(%L)", h.className.nestedClass("Deps"), args.joinToCode(", ")),
                handles = h.event,
            )
        }
        val members = containers + handlers
        val clashes = members.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(clashes.isEmpty()) { "Scope '${scope.name}': bindings $clashes would clash - rename a container's field or give a handler a `name`" }
        return members
    }

    internal fun generate(context: GeneratorContext, scope: ScopeModel): FileSpec {
        val base = baseName(scope)
        val pkg = context.packages.scopes
        val paramsType = scope.params?.let(context.types::typeOf) ?: UNIT
        val hasParams = scope.params != null
        val keyName = context.scopeKeyName(scope)
        val scopeClass = ClassName(pkg, "${base}Scope")
        val bindingsType = ClassName(pkg, "${base}ScopeBindings")
        val adapterType = ClassName(pkg, "${base}ScopeDependencyBindings")
        val members = membersOf(context, scope)

        val key = TypeSpec.objectBuilder(keyName)
            .addSuperinterface(CoreSymbols.ScopeKey.parameterizedBy(paramsType))
            .addProperty(
                PropertySpec.builder("identity", STRING, KModifier.OVERRIDE).initializer("%S", scope.name).build()
            )
            .build()

        // One factory per container and handler - so a container or handler added to the schema
        // breaks every implementation of this interface at compile time until its part is written.
        fun memberFun(member: Member) = FunSpec.builder(member.name)
            .apply { member.deps?.let { addParameter("deps", it) } }
            .apply { if (hasParams) addParameter("params", paramsType) }
            .returns(member.returns)
        val bindings = TypeSpec.interfaceBuilder(bindingsType)
            .addKdoc(
                "Everything [%N] is built from: one factory per container and handler of the schema. Implement it\n" +
                    "directly, or use [%N] to resolve each from a DependencyProvider.", scopeClass.simpleName, adapterType.simpleName,
            )
            .addFunctions(members.map { memberFun(it).addModifiers(KModifier.ABSTRACT).build() })
            .addFunction(
                FunSpec.builder("eventBus").returns(CoreSymbols.EventBus.copy(nullable = true))
                    .addKdoc("This scope's bus; `null` (default): one bound in the DependencyProvider under the scope's name, else a default bus.")
                    .addStatement("return null")
                    .build()
            )
            .build()

        val adapter = TypeSpec.classBuilder(adapterType)
            .addKdoc("[%N] resolved from a DependencyProvider - see DependencyProviderBindings for the binding conventions.", bindingsType.simpleName)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("dependencyProvider", CoreSymbols.DependencyProvider).build())
            .superclass(CoreSymbols.DependencyProviderBindings)
            .addSuperclassConstructorParameter("dependencyProvider")
            .addSuperclassConstructorParameter("%S", scope.name)
            .addSuperinterface(bindingsType)
            .addFunctions(members.map { m ->
                val args = listOfNotNull(m.deps?.let { "deps" }, "params".takeIf { hasParams })
                memberFun(m).addModifiers(KModifier.OVERRIDE)
                    .addStatement("return resolve(%T::class%L)", m.returns, args.joinToString("") { ", $it" })
                    .build()
            })
            .addFunction(
                FunSpec.builder("eventBus").addModifiers(KModifier.OVERRIDE).returns(CoreSymbols.EventBus.copy(nullable = true))
                    .addStatement("return resolveEventBus()")
                    .build()
            )
            .build()

        val type = TypeSpec.classBuilder(scopeClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .apply { if (hasParams) addParameter("params", paramsType) }
                    .addParameter("bindings", bindingsType)
                    .addParameter(
                        ParameterSpec.builder("dependencyProvider", CoreSymbols.DependencyProvider)
                            .defaultValue("%T()", CoreSymbols.DummyProvider).build()
                    )
                    .build()
            )
            .addProperty(PropertySpec.builder("bindings", bindingsType, KModifier.PRIVATE).initializer("bindings").build())
            .addFunction(
                FunSpec.constructorBuilder()
                    .addKdoc("Built from [dependencyProvider] via [%N].", adapterType.simpleName)
                    .apply { if (hasParams) addParameter("params", paramsType) }
                    .addParameter("dependencyProvider", CoreSymbols.DependencyProvider)
                    .callThisConstructor(
                        *listOfNotNull("params".takeIf { hasParams }, "%T(dependencyProvider)", "dependencyProvider")
                            .map { if (it.startsWith("%T")) CodeBlock.of(it, adapterType) else CodeBlock.of(it) }
                            .toTypedArray()
                    )
                    .build()
            )
            .superclass(CoreSymbols.ScopeTyped.parameterizedBy(paramsType))
            .addSuperclassConstructorParameter("scopeKey = %N", keyName)
            .addSuperclassConstructorParameter(if (hasParams) "params = params" else "params = Unit")
            .apply { scope.description?.let { addSuperclassConstructorParameter("description = %S", it) } }
            .addSuperclassConstructorParameter("dependencyProvider = dependencyProvider")
            .addSuperclassConstructorParameter("eventBusFactory = { bindings.eventBus() }")
            .apply { scope.description?.let { addKdoc("%L", it) } }
            .addFunction(wire(members, hasParams))
            .build()

        val fragmentName = "${base}ScopeFragment"
        val fragment = PropertySpec.builder(fragmentName, CoreSymbols.ScopeKeyFragment.parameterizedBy(paramsType))
            .addKdoc("Relations of [%N]: what loading it also loads, which events other scopes route into it.", scopeClass.simpleName)
            .initializer(fragment(context, scope, keyName))
            .build()

        // `extend`, not `by`: `by` would register a DSL scope under this key - the typed one is
        // registered by scopeTyped right next to it.
        val registerDi = FunSpec.builder("register${base}Scope")
            .receiver(CoreSymbols.ScopeHolderBuilder)
            .addKdoc("Registers [%N] with its relations, built from this holder's DependencyProvider ([%N]).", scopeClass.simpleName, adapterType.simpleName)
            .apply {
                if (hasParams) addStatement("scopeTyped(%N) { params, provider -> %T(params, provider) }", keyName, scopeClass)
                else addStatement("scopeTyped(%N) { provider -> %T(provider) }", keyName, scopeClass)
            }
            .addStatement("%M(%N)", CoreSymbols.include, fragmentName)
            .build()
        val bindingsFactory = if (hasParams) {
            LambdaTypeName.get(parameters = arrayOf(paramsType, CoreSymbols.DependencyProvider), returnType = bindingsType)
        } else {
            LambdaTypeName.get(parameters = arrayOf(CoreSymbols.DependencyProvider), returnType = bindingsType)
        }
        val registerBindings = FunSpec.builder("register${base}Scope")
            .receiver(CoreSymbols.ScopeHolderBuilder)
            .addKdoc("Registers [%N] with its relations, built from [bindings] - checked for completeness at compile time.", scopeClass.simpleName)
            .addParameter("bindings", bindingsFactory)
            .apply {
                if (hasParams) addStatement("scopeTyped(%N) { params, provider -> %T(params, bindings(params, provider), provider) }", keyName, scopeClass)
                else addStatement("scopeTyped(%N) { provider -> %T(bindings(provider), provider) }", keyName, scopeClass)
            }
            .addStatement("%M(%N)", CoreSymbols.include, fragmentName)
            .build()

        return FileSpec.builder(pkg, "${base}Scope")
            .addFileComment(GENERATED)
            .addType(key)
            .addType(bindings)
            .addType(adapter)
            .addType(type)
            .addProperty(fragment)
            .addFunction(registerDi)
            .addFunction(registerBindings)
            .build()
    }

    private fun fragment(context: GeneratorContext, scope: ScopeModel, keyName: String): CodeBlock {
        val body = CodeBlock.builder()
        scope.dependsOn.forEach { body.addStatement("dependsOn(%T)", context.dependencyKey(scope, it)) }
        scope.consumes.forEach { consumed ->
            val input = scope.handlers.first { it.input.name == consumed }.input
            body.addStatement("consume<%T>()", context.types.eventType(input))
        }
        return CodeBlock.builder()
            .beginControlFlow("%T.%M(%N)", CoreSymbols.ScopeKey, CoreSymbols.extend, keyName)
            .add(body.build())
            .endControlFlow()
            .build()
    }

    /** Builds every member through `bindings`, in order - no lookups left here. */
    private fun wire(members: List<Member>, hasParams: Boolean): FunSpec {
        val body = CodeBlock.builder()
        members.forEach { m ->
            val args = listOfNotNull(m.build, CodeBlock.of("params").takeIf { hasParams }).joinToCode(", ")
            val handles = m.handles
            if (handles == null) {
                body.addStatement("val %N = container(%S, bindings.%N(%L))", m.name, m.name, m.name, args)
            } else {
                body.addStatement("on<%T>(bindings.%N(%L))", handles, m.name, args)
            }
        }
        return FunSpec.builder("wire")
            .addModifiers(KModifier.OVERRIDE)
            .receiver(CoreSymbols.ScopeWiring)
            .addCode(body.build())
            .build()
    }

    companion object {
        /** `Todo` and `TodoScope` both become `TodoScope`. */
        fun className(scope: ScopeModel): String = "${baseName(scope)}Scope"

        private fun baseName(scope: ScopeModel) = scope.name.removeSuffix("Scope")
    }
}

internal const val GENERATED = "Generated by event-thread-codegen. Do not edit."
