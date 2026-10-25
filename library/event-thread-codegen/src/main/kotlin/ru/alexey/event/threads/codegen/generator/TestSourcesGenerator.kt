package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.joinToCode
import ru.alexey.event.threads.codegen.model.HandlerModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel

/**
 * Test sources, per scope, in its `.scopes` package (generated into the test source set):
 * - `<Name>ScopeContract` - abstract; extend it with your `bindings()` (and `params()`). Its tests
 *   check every binding is provided, handlers and relations are registered as the schema says,
 *   and each schema `example` holds against your handler implementation;
 * - `<Name>ScopeProbe` - a [ru.alexey.event.threads.test.typed.TypedScopeProbe] with typed access
 *   to the scope's containers, for your own behaviour tests.
 */
class TestSourcesGenerator(private val packageName: String) {

    fun generate(scopes: List<ScopeModel>): List<FileSpec> = generate(GeneratorContext(packageName, scopes))

    internal fun generate(context: GeneratorContext): List<FileSpec> = context.scopes.flatMap { scope ->
        listOf(contract(context, scope), probe(context, scope))
    }

    private class Names(context: GeneratorContext, scope: ScopeModel) {
        val base = ScopeGenerator.className(scope).removeSuffix("Scope")
        val pkg = context.packages.scopes
        val scope = ClassName(pkg, "${base}Scope")
        val bindings = ClassName(pkg, "${base}ScopeBindings")
        val register = MemberName(pkg, "register${base}Scope")
    }

    // --- contract ---------------------------------------------------------------------------

    private fun contract(context: GeneratorContext, scope: ScopeModel): FileSpec {
        val n = Names(context, scope)
        val hasParams = scope.params != null
        val paramsType = scope.params?.let(context.types::typeOf) ?: UNIT
        val newScope = if (hasParams) CodeBlock.of("%T(params(), bindings())", n.scope) else CodeBlock.of("%T(bindings())", n.scope)

        val type = TypeSpec.classBuilder("${n.base}ScopeContract")
            .addModifiers(KModifier.ABSTRACT)
            .addAnnotation(AnnotationSpec.builder(OPT_IN).addMember("%T::class", EXPERIMENTAL_COROUTINES).build())
            .addKdoc(
                "Contract of [%T] against the schema - extend it with the implementation under test:\n" +
                    "hand-written bindings, or `%TDependencyBindings(yourProvider)`.", n.scope, n.scope,
            )
            .addFunction(FunSpec.builder("bindings").addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT).returns(n.bindings).build())
            .apply {
                if (hasParams) addFunction(
                    FunSpec.builder("params").addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT).returns(paramsType)
                        .addKdoc("Params to build the scope with in these tests.").build()
                )
            }
            .addProperty(
                PropertySpec.builder("replaceMainDispatcher", Boolean::class, KModifier.PROTECTED, KModifier.OPEN)
                    .addKdoc("Runs Dispatchers.Main on a test dispatcher for each test - for implementations pinned to Main.")
                    .initializer("true").build()
            )
            .addFunction(
                FunSpec.builder("setUpMain").addAnnotation(BEFORE_TEST)
                    .addStatement("if (replaceMainDispatcher) %T.%M(%T())", DISPATCHERS, SET_MAIN, UNCONFINED).build()
            )
            .addFunction(
                FunSpec.builder("tearDownMain").addAnnotation(AFTER_TEST)
                    .addStatement("if (replaceMainDispatcher) %T.%M()", DISPATCHERS, RESET_MAIN).build()
            )
            .addFunction(
                test("everyBindingIsProvided")
                    .addKdoc("Builds every container and handler through [bindings] - a missing DependencyProvider binding fails here.")
                    .addStatement("val scope = %L", newScope)
                    .beginControlFlow("try")
                    .addStatement("scope.eventBus")
                    .nextControlFlow("finally")
                    .addStatement("scope.close()")
                    .endControlFlow()
                    .build()
            )
            .addFunction(handlersRegistered(context, scope, newScope))
            .addFunction(eventFlow(context, scope, newScope))
            .addFunction(relationsRegistered(context, scope, n, hasParams))
            .addFunctions(scope.handlers.flatMap { examples(context, scope, it, hasParams) })
            .build()

        return FileSpec.builder(n.pkg, "${n.base}ScopeContract")
            .generatedBy(context)
            .addType(type)
            .build()
    }

    /** Each event has as many handlers registered on the live scope as the schema declares. */
    private fun handlersRegistered(context: GeneratorContext, scope: ScopeModel, newScope: CodeBlock): FunSpec {
        val counts = scope.handlers.groupingBy { context.types.eventType(it.input) }.eachCount()
        return test("handlersAreRegistered")
            .addStatement("val scope = %L", newScope)
            .addStatement("val handled = scope.metadata.eventsMetadata")
            .apply {
                counts.forEach { (event, n) ->
                    addStatement(
                        "%M(%L, handled[%T::class.simpleName]?.actions?.size ?: 0, %S)",
                        ASSERT_EQUALS, n, event, "handlers registered for ${event.simpleName}",
                    )
                }
            }
            .addStatement("scope.close()")
            .build()
    }

    /**
     * The live scope's event flow - from its registration metadata, as event-thread-test's graph
     * tools read it - is the schema's: per event exactly the declared cascades, each reachable.
     */
    private fun eventFlow(context: GeneratorContext, scope: ScopeModel, newScope: CodeBlock): FunSpec {
        val produced = scope.handlers.groupBy { context.types.eventType(it.input) }.mapValues { (_, hs) ->
            hs.flatMap { h -> h.output.cascades.map(context.types::eventType) }.distinct()
        }
        return test("eventFlowMatchesSchema")
            .addStatement("val scope = %L", newScope)
            .addStatement("val events = scope.metadata.eventsMetadata")
            .addStatement("val graph = scope.metadata.%M(%S)", TO_EVENT_GRAPH, scope.name)
            .apply {
                produced.forEach { (event, outputs) ->
                    addStatement(
                        "%M(setOf<String>(%L), events[%T::class.simpleName]?.producedTypes.orEmpty(), %S)",
                        ASSERT_EQUALS, outputs.map { CodeBlock.of("%S", it.simpleName) }.joinToCode(", "), event,
                        "events ${event.simpleName} cascades into",
                    )
                    if (outputs.isNotEmpty()) addStatement(
                        "graph.%M(%S, %S, %L)", ASSERT_REACHES, scope.name, event.simpleName,
                        outputs.map { CodeBlock.of("%S", it.simpleName) }.joinToCode(", "),
                    )
                }
            }
            .addStatement("scope.close()")
            .build()
    }

    private fun relationsRegistered(context: GeneratorContext, scope: ScopeModel, n: Names, hasParams: Boolean): FunSpec {
        val deps = scope.dependsOn.map { CodeBlock.of("%T.identity", context.dependencyKey(scope, it)) }
        return test("relationsAreRegistered")
            .addStatement(
                if (hasParams) "val holder = %M { %M { _, _ -> bindings() } }" else "val holder = %M { %M { _ -> bindings() } }",
                SCOPE_HOLDER, n.register,
            )
            .addStatement(
                "%M(listOf<String>(%L), holder.dependencies[%S].orEmpty())", ASSERT_EQUALS, deps.joinToCode(", "), scope.name,
            )
            .apply {
                scope.consumes.forEach { consumed ->
                    val type = context.types.eventType(scope.handlers.first { it.input.name == consumed }.input)
                    addStatement(
                        "%M(%S in holder.external[%T::class].orEmpty(), %S)",
                        ASSERT_TRUE, scope.name, type, "${type.simpleName} isn't routed to ${scope.name}",
                    )
                }
            }
            .addStatement("holder.close()")
            .build()
    }

    /** One test per example: the handler from [bindings], its containers stubbed with the example's
     * state - so only the implementation's logic is under test. */
    private fun examples(context: GeneratorContext, scope: ScopeModel, model: HandlerModel, hasParams: Boolean): List<FunSpec> {
        if (model.examples.isEmpty()) return emptyList()
        if (model.output is HandlerOutput.Request) return requestExamples(context, scope, model, model.output, hasParams)
        val values = ExampleValues(context.types)
        val handler = context.handlerOf(model)
        val factory = handler.className.simpleName.replaceFirstChar(Char::lowercaseChar)
        return model.examples.mapIndexed { index, example ->
            val label = example.name ?: "$index"
            val where = "${scope.name}.${handler.className.simpleName}[$label]"
            val body = CodeBlock.builder()

            fun stub(stateArg: ru.alexey.event.threads.codegen.model.ArgDef, json: kotlinx.serialization.json.JsonElement, local: String) {
                val c = context.container(stateArg)
                require(c.from == null) { "Example $where: ${c.className.simpleName} is derived - examples can't stub derived containers" }
                body.addStatement(
                    "val %N = object : %T() { override val source = %M<%T>(%L) }",
                    local, c.className, FLOW_RESOURCE, c.state, values.of(stateArg, json, "$where.$local"),
                )
            }

            val own = handler.ownState
            if (own != null) stub(own, example.given!!, context.container(own).property)
            handler.reads.forEach { read ->
                val name = context.container(read).property
                val given = example.reads[name] ?: throw IllegalArgumentException("Example $where: needs `reads.$name`")
                stub(read, given, name)
            }
            val depsArgs = listOfNotNull(own?.let { CodeBlock.of("%N", context.container(it).property) }) +
                handler.reads.map { CodeBlock.of("%N.%M()", context.container(it).property, CoreSymbols.snapshot) }
            val args = listOfNotNull(
                CodeBlock.of("%T(%L)", handler.className.nestedClass("Deps"), depsArgs.joinToCode(", ")).takeIf { depsArgs.isNotEmpty() },
                CodeBlock.of("params()").takeIf { hasParams },
            )
            body.addStatement("val handler = bindings().%N(%L)", factory, args.joinToCode(", "))
            val event = values.event(model.input, example.event, "$where.event")

            when (val output = model.output) {
                is HandlerOutput.Data -> {
                    body.addStatement("handler.handle(%L)", event)
                    body.addStatement("%M(%L, %N.value)", ASSERT_EQUALS, values.of(output.type, example.expect!!, "$where.expect"), context.container(output.type).property)
                }
                is HandlerOutput.Event -> {
                    val expected = example.emits!!.mapIndexed { i, e -> values.event(output.event, e, "$where.emits[$i]") }
                    body.addStatement("%M(listOf<%T>(%L), handler.preview(%L))", ASSERT_EQUALS, CoreSymbols.Event, expected.joinToCode(", "), event)
                }
                HandlerOutput.None, is HandlerOutput.Request -> Unit
            }
            (listOfNotNull(own) + handler.reads).forEach { body.addStatement("%N.close()", context.container(it).property) }

            test("${factory}_example_$label")
                .addKdoc("Schema example `%L` of %L.", label, handler.className.simpleName)
                // TestResult, not Unit: on JS runTest returns a Promise the test runner must get.
                .returns(TEST_RESULT)
                .addCode("return %M {\n", RUN_TEST).addCode(body.build()).addCode("}\n")
                .build()
        }
    }

    /**
     * A request's examples: its container stubbed with `given` (as the last loaded value), the
     * API faked - the called endpoint responds `respond` or throws `fail` - then one
     * `execute(event)`: the container must end `Success(respond)` (or `Failure` with that message,
     * keeping `given`), and the events must be `emits`.
     */
    private fun requestExamples(
        context: GeneratorContext,
        scope: ScopeModel,
        model: HandlerModel,
        request: HandlerOutput.Request,
        hasParams: Boolean,
    ): List<FunSpec> {
        val values = ExampleValues(context.types)
        val handler = context.handlerOf(model)
        val factory = handler.className.simpleName.replaceFirstChar(Char::lowercaseChar)
        val (api, endpoint) = context.endpointOf(request)
        val into = context.container(request.into)
        val response = (request.into as ru.alexey.event.threads.codegen.model.ArgDef.Generics).parameters.single()
        return model.examples.mapIndexed { index, example ->
            val label = example.name ?: "$index"
            val where = "${scope.name}.${handler.className.simpleName}[$label]"
            val body = CodeBlock.builder()
            val previous = example.given?.let { values.of(response, it, "$where.given") }
            body.addStatement(
                "val %N = object : %T() { override val source = %M<%T>(%L) }",
                into.property, into.className, FLOW_RESOURCE, into.state,
                previous?.let { CodeBlock.of("%T.Success(%L)", CoreSymbols.RemoteData, it) } ?: CodeBlock.of("%T.Idle", CoreSymbols.RemoteData),
            )
            handler.reads.forEach { read ->
                val c = context.container(read)
                val given = example.reads[c.property] ?: throw IllegalArgumentException("Example $where: needs `reads.${c.property}`")
                body.addStatement(
                    "val %N = object : %T() { override val source = %M<%T>(%L) }",
                    c.property, c.className, FLOW_RESOURCE, c.state, values.of(read, given, "$where.${c.property}"),
                )
            }
            val responded = if (example.fail != null) {
                CodeBlock.of("throw %T(%S)", ClassName("kotlin", "IllegalStateException"), example.fail)
            } else if (endpoint.response == null) {
                CodeBlock.of("Unit")
            } else {
                CodeBlock.of("return %L", values.of(endpoint.response, example.respond!!, "$where.respond"))
            }
            val fake = TypeSpec.anonymousClassBuilder().addSuperinterface(context.apiClass(api))
                .addFunctions(api.endpoints.map { e ->
                    FunSpec.builder(e.name).addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                        .apply { e.args.forEach { addParameter(it.fieldName!!, context.types.typeOf(it)) } }
                        .returns(context.responseType(e))
                        .apply {
                            if (e != endpoint) addStatement("error(%S)", "${api.name}.${e.name} isn't called by this example")
                            else if (endpoint.response == null && example.fail == null) addStatement("return Unit")
                            else addStatement("%L", responded)
                        }
                        .build()
                })
                .build()
            body.addStatement("val %N = %L", context.apiProperty(api), fake)
            val deps = listOf(CodeBlock.of("%N", into.property)) +
                handler.reads.map { CodeBlock.of("%N.%M()", context.container(it).property, CoreSymbols.snapshot) } +
                CodeBlock.of("%N", context.apiProperty(api))
            val args = listOfNotNull(
                CodeBlock.of("%T(%L)", handler.className.nestedClass("Deps"), deps.joinToCode(", ")),
                CodeBlock.of("params()").takeIf { hasParams },
            )
            body.addStatement("val handler = bindings().%N(%L)", factory, args.joinToCode(", "))
            body.addStatement("val emitted = handler.execute(%L)", values.event(model.input, example.event, "$where.event"))
            if (example.fail != null) {
                body.addStatement("val state = %N.value", into.property)
                body.addStatement("%M(state is %T.Failure && state.error.message == %S, %P)", ASSERT_TRUE, CoreSymbols.RemoteData, example.fail, "expected Failure(${example.fail}), was \$state")
                body.addStatement("%M(%L, state.valueOrNull)", ASSERT_EQUALS, previous ?: CodeBlock.of("null"))
            } else {
                val value = endpoint.response?.let { values.of(it, example.respond!!, "$where.respond") } ?: CodeBlock.of("Unit")
                body.addStatement("%M(%T.Success(%L), %N.value)", ASSERT_EQUALS, CoreSymbols.RemoteData, value, into.property)
            }
            val expected = example.emits.orEmpty().mapIndexed { i, e -> emitted(values, request, e, "$where.emits[$i]") }
            body.addStatement("%M(listOf<%T>(%L), emitted)", ASSERT_EQUALS, CoreSymbols.Event, expected.joinToCode(", "))
            (listOf(request.into) + handler.reads).forEach { body.addStatement("%N.close()", context.container(it).property) }

            test("${factory}_example_$label")
                .addKdoc("Schema example `%L` of %L (`%L`).", label, handler.className.simpleName, request.call)
                .returns(TEST_RESULT)
                .addCode("return %M {\n", RUN_TEST).addCode(body.build()).addCode("}\n")
                .build()
        }
    }

    /** An emitted event: its args - or, when the request emits several kinds, `{ "<Event>": args }`. */
    private fun emitted(values: ExampleValues, request: HandlerOutput.Request, json: kotlinx.serialization.json.JsonElement, where: String): CodeBlock {
        if (request.emits.size == 1) return values.event(request.emits.single(), json, where)
        val tagged = (json as? kotlinx.serialization.json.JsonObject)?.entries?.singleOrNull()
            ?: throw IllegalArgumentException("Example $where: with several `emits`, write each event as { \"<Event>\": { args } }")
        val event = request.emits.find { it.simpleName == tagged.key }
            ?: throw IllegalArgumentException("Example $where: '${tagged.key}' isn't one of the request's emits ${request.emits.map { it.simpleName }}")
        return values.event(event, tagged.value.takeUnless { it is kotlinx.serialization.json.JsonNull }, where)
    }

    // --- probe ------------------------------------------------------------------------------

    private fun probe(context: GeneratorContext, scope: ScopeModel): FileSpec {
        val n = Names(context, scope)
        val hasParams = scope.params != null
        val paramsType = scope.params?.let(context.types::typeOf) ?: UNIT
        val containers = ScopeGenerator(packageName).membersOf(context, scope).filter { it.kind == ScopeGenerator.Kind.Container }
        val probe = ClassName(n.pkg, "${n.base}ScopeProbe")
        val recording = probe.nestedClass("Recording")

        // Wraps the bindings to keep the container instances the scope gets - typed access to
        // the very containers under test, no lookups or casts.
        val recordingType = TypeSpec.classBuilder(recording)
            .addModifiers(KModifier.PRIVATE)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("wrapped", n.bindings).build())
            .addProperty(PropertySpec.builder("wrapped", n.bindings, KModifier.PRIVATE).initializer("wrapped").build())
            .addSuperinterface(n.bindings, CodeBlock.of("wrapped"))
            .addProperties(containers.map {
                PropertySpec.builder(it.name, it.returns.copy(nullable = true)).mutable().initializer("null").build()
            })
            .addFunctions(containers.map { m ->
                val args = listOfNotNull(m.deps?.let { "deps" }, "params".takeIf { hasParams })
                FunSpec.builder(m.name).addModifiers(KModifier.OVERRIDE)
                    .apply { m.deps?.let { addParameter("deps", it) } }
                    .apply { if (hasParams) addParameter("params", paramsType) }
                    .returns(m.returns)
                    .addStatement("return wrapped.%N(%L).also { %N = it }", m.name, args.joinToString(", "), m.name)
                    .build()
            })
            .build()

        val primary = FunSpec.constructorBuilder().addModifiers(KModifier.PRIVATE)
            .addParameter("recording", recording)
            .apply { if (hasParams) addParameter("params", paramsType) }
            .build()
        val type = TypeSpec.classBuilder(probe)
            .addKdoc("[%T] under test, with typed access to its containers - built from [bindings].", n.scope)
            .primaryConstructor(primary)
            .addProperty(PropertySpec.builder("recording", recording, KModifier.PRIVATE).initializer("recording").build())
            .superclass(TYPED_SCOPE_PROBE)
            .addSuperclassConstructorParameter(if (hasParams) "%T(params, recording)" else "%T(recording)", n.scope)
            .addFunction(
                FunSpec.constructorBuilder()
                    .apply { if (hasParams) addParameter("params", paramsType) }
                    .addParameter("bindings", n.bindings)
                    .callThisConstructor(*listOfNotNull(CodeBlock.of("%T(bindings)", recording), CodeBlock.of("params").takeIf { hasParams }).toTypedArray())
                    .build()
            )
            .addProperties(containers.map {
                PropertySpec.builder(it.name, it.returns)
                    .getter(FunSpec.getterBuilder().addStatement("return checkNotNull(recording.%N)", it.name).build())
                    .build()
            })
            .addType(recordingType)
            .build()

        return FileSpec.builder(n.pkg, "${n.base}ScopeProbe")
            .generatedBy(context)
            .addType(type)
            .build()
    }

    private fun test(name: String) = FunSpec.builder(name).addAnnotation(TEST)

    private companion object {
        val TEST = ClassName("kotlin.test", "Test")
        val BEFORE_TEST = ClassName("kotlin.test", "BeforeTest")
        val AFTER_TEST = ClassName("kotlin.test", "AfterTest")
        val ASSERT_EQUALS = MemberName("kotlin.test", "assertEquals")
        val ASSERT_TRUE = MemberName("kotlin.test", "assertTrue")
        val OPT_IN = ClassName("kotlin", "OptIn")
        val EXPERIMENTAL_COROUTINES = ClassName("kotlinx.coroutines", "ExperimentalCoroutinesApi")
        val DISPATCHERS = ClassName("kotlinx.coroutines", "Dispatchers")
        val SET_MAIN = MemberName("kotlinx.coroutines.test", "setMain")
        val RESET_MAIN = MemberName("kotlinx.coroutines.test", "resetMain")
        val UNCONFINED = ClassName("kotlinx.coroutines.test", "UnconfinedTestDispatcher")
        val RUN_TEST = MemberName("kotlinx.coroutines.test", "runTest")
        val TEST_RESULT = ClassName("kotlinx.coroutines.test", "TestResult")
        val FLOW_RESOURCE = MemberName("ru.alexey.event.threads.resources", "flowResource")
        val SCOPE_HOLDER = MemberName("ru.alexey.event.threads.scopeholder", "scopeHolder")
        val TYPED_SCOPE_PROBE = ClassName("ru.alexey.event.threads.test.typed", "TypedScopeProbe")
        val TO_EVENT_GRAPH = MemberName("ru.alexey.event.threads.test.graph", "toEventGraph")
        val ASSERT_REACHES = MemberName("ru.alexey.event.threads.test.graph", "assertReaches")
    }
}
