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
    internal enum class Kind { Container, Client, Api, Handler }

    internal class Member(
        val name: String,
        val returns: ClassName,
        val deps: ClassName?,
        val build: CodeBlock?,
        val handles: ClassName?,
        /** `<Name>Rule(...)`: the default implementation, generated from the schema rule. */
        val default: CodeBlock? = null,
        val defaultClass: ClassName? = null,
        val kind: Kind = if (handles == null) Kind.Container else Kind.Handler,
    )

    internal fun membersOf(context: GeneratorContext, scope: ScopeModel): List<Member> {
        val containers = context.containersOf(scope).map { c ->
            val from = c.from
            val initial = context.initials[scope.name to c.property]
            Member(
                name = c.property,
                returns = c.className,
                deps = from?.let { c.className.nestedClass("Deps") },
                build = from?.let { states ->
                    CodeBlock.of("%T(%L)", c.className.nestedClass("Deps"),
                        states.map { CodeBlock.of("%N", context.container(it).property) }.joinToCode(", "))
                },
                handles = null,
                default = initial?.let { CodeBlock.of("%T(%L)", context.initialClass(c), it.body) },
                defaultClass = context.initialClass(c).takeIf { initial != null },
            )
        }
        // Remote APIs the scope's requests call; a Ktor default needs the HttpClient.
        val apis = context.apisOf(scope)
        val ktorApis = apis.filter { context.ktor && it.isHttp }
        val client = if (ktorApis.isEmpty()) emptyList() else listOf(
            Member(
                name = "httpClient", returns = HTTP_CLIENT, deps = null, build = null, handles = null,
                default = CodeBlock.of(
                    "error(%S)",
                    "No HttpClient for scope '${scope.name}' - bind one in its DependencyProvider, or override ${ktorApis.joinToString { context.apiProperty(it) + "()" }}",
                ),
                defaultClass = null, kind = Kind.Client,
            )
        )
        val apiMembers = apis.map { api ->
            val ktor = api in ktorApis
            Member(
                name = context.apiProperty(api), returns = context.apiClass(api), deps = null, build = null, handles = null,
                default = CodeBlock.of("%T(httpClient(%L))", context.ktorClass(api), if (scope.params != null) "params" else "").takeIf { ktor },
                defaultClass = context.ktorClass(api).takeIf { ktor }, kind = Kind.Api,
            )
        }
        val handlers = scope.handlers.map(context::handlerOf).distinct().map { h ->
            val args = listOfNotNull(h.writes?.let { CodeBlock.of("%N", context.container(it).property) }) +
                h.reads.map { CodeBlock.of("%N.%M()", context.container(it).property, CoreSymbols.snapshot) } +
                listOfNotNull(h.request?.let { CodeBlock.of("%N", context.apiProperty(context.endpointOf(it).first)) })
            Member(
                name = h.className.simpleName.replaceFirstChar(Char::lowercaseChar),
                returns = h.className,
                deps = if (args.isEmpty()) null else h.className.nestedClass("Deps"),
                // joinToCode, not joinToString: a stringified CodeBlock loses its imports.
                build = if (args.isEmpty()) null else CodeBlock.of("%T(%L)", h.className.nestedClass("Deps"), args.joinToCode(", ")),
                handles = h.event,
                default = (context.rules[h] ?: context.requests[h])?.let {
                    val ruleArgs = listOfNotNull("deps".takeIf { args.isNotEmpty() }, "params".takeIf { context.ruleParams(h) != null })
                    CodeBlock.of("%T(%L)", h.ruleClassName, ruleArgs.joinToString(", "))
                },
                defaultClass = h.ruleClassName.takeIf { context.rules[h] != null || context.requests[h] != null },
            )
        }
        val members = containers + client + apiMembers + handlers
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
            .addFunctions(members.map { m ->
                val default = m.default
                if (default == null) {
                    memberFun(m).addModifiers(KModifier.ABSTRACT).build()
                } else {
                    memberFun(m)
                        .apply {
                            when (m.kind) {
                                Kind.Client -> addKdoc("The HttpClient the generated Ktor APIs use - its base URL, JSON setup, auth.")
                                Kind.Api -> addKdoc("Default: [%T] over [httpClient] - override for another transport, or a fake.", m.defaultClass!!)
                                Kind.Container -> addKdoc("Default: [%T], generated from the schema's `initial` - override for an implementation of your own.", m.defaultClass!!)
                                Kind.Handler -> addKdoc("Default: [%T], generated from the schema - override for an implementation of your own.", m.defaultClass!!)
                            }
                        }
                        .addStatement("return %L", default)
                        .build()
                }
            })
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
                val resolved = args.joinToString("") { ", $it" }
                val call = memberFun(m).addModifiers(KModifier.OVERRIDE)
                // A rule's default stays the fallback: one bound in the provider wins over it.
                if (m.default == null) call.addStatement("return resolve(%T::class%L)", m.returns, resolved)
                else call.addStatement("return resolveOrNull(%T::class%L) ?: super<%T>.%N(%L)", m.returns, resolved, bindingsType, m.name, args.joinToString(", "))
                call.build()
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
            .addProperties(typedContainers(context, scope))
            .addFunctions(senders(context, scope))
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
            .generatedBy(context)
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

    /** One property per container, as its own class - `workScope.todos: TodosContainer`, a
     * `StateFlow` for the UI (`todos.collectAsState()`). */
    private fun typedContainers(context: GeneratorContext, scope: ScopeModel): List<PropertySpec> =
        context.containersOf(scope).map { c ->
            require(c.property !in RESERVED) {
                "Scope '${scope.name}': container '${c.property}' would hide the scope's own member - rename its field"
            }
            PropertySpec.builder(c.property, c.className)
                .getter(FunSpec.getterBuilder().addStatement("return typedContainer(%S)", c.property).build())
                .build()
        }

    /** `send(event)` per event the scope handles - sending one it doesn't handle is a compile error. */
    private fun senders(context: GeneratorContext, scope: ScopeModel): List<FunSpec> =
        scope.handlers.map { context.types.eventType(it.input) }.distinct().map { event ->
            FunSpec.builder("send")
                .addKdoc("Dispatches [%T] into this scope.", event)
                .addParameter("event", event)
                .addStatement("plus(event)")
                .build()
        }

    /** Builds every member through `bindings`, in order - no lookups left here. */
    private fun wire(members: List<Member>, hasParams: Boolean): FunSpec {
        val body = CodeBlock.builder()
        members.forEach { m ->
            val args = listOfNotNull(m.build, CodeBlock.of("params").takeIf { hasParams }).joinToCode(", ")
            when (m.kind) {
                Kind.Container -> body.addStatement("val %N = container(%S, bindings.%N(%L))", m.name, m.name, m.name, args)
                Kind.Api -> body.addStatement("val %N = bindings.%N(%L)", m.name, m.name, args)
                Kind.Client -> Unit   // only the Ktor APIs' defaults call it
                Kind.Handler -> body.addStatement("on<%T>(bindings.%N(%L))", m.handles!!, m.name, args)
            }
        }
        return FunSpec.builder("wire")
            .addModifiers(KModifier.OVERRIDE)
            .receiver(CoreSymbols.ScopeWiring)
            .addCode(body.build())
            .build()
    }

    companion object {
        val HTTP_CLIENT = ClassName("io.ktor.client", "HttpClient")

        /** Members of `ScopeTyped`/`Scope` a container property must not shadow. */
        private val RESERVED = setOf(
            "key", "scopeKey", "params", "description", "dependencyProvider", "eventBus", "metadata", "emitters",
            "bindings", "close", "get", "resolve", "resolveOrThrow", "plus", "thread", "send", "wire", "typedContainer",
        )

        /** `Todo` and `TodoScope` both become `TodoScope`. */
        fun className(scope: ScopeModel): String = "${baseName(scope)}Scope"

        private fun baseName(scope: ScopeModel) = scope.name.removeSuffix("Scope")
    }
}

internal const val GENERATED = "Generated by event-thread-codegen. Do not edit."

/**
 * Marks a generated file: the [GeneratorContext.header] comment, plus `@file:Suppress` for the
 * inspections that are noise on code nobody edits - `unused` (a probe or adapter for a scope your
 * tests don't touch yet) and redundant explicit `public` (KotlinPoet writes it everywhere).
 */
internal fun com.squareup.kotlinpoet.FileSpec.Builder.generatedBy(context: GeneratorContext): com.squareup.kotlinpoet.FileSpec.Builder =
    addFileComment("%L", context.header)
        .addAnnotation(
            com.squareup.kotlinpoet.AnnotationSpec.builder(Suppress::class)
                .useSiteTarget(com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget.FILE)
                .addMember("%S, %S", "unused", "RedundantVisibilityModifier")
                .build()
        )

/** [GENERATED], plus what produced it - so a stale generated file is recognisable as one. */
internal fun header(generatorVersion: String?, document: ru.alexey.event.threads.codegen.model.ScopeDocument): String =
    GENERATED + "\n" + listOfNotNull(
        generatorVersion?.let { "event-thread-codegen $it" },
        "schema ${document.version ?: "unversioned"}",
        "format ${document.format}",
    ).joinToString(", ")
