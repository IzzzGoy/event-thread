package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import ru.alexey.event.threads.OutputMismatchBehavior
import ru.alexey.event.threads.Privacy
import ru.alexey.event.threads.codegen.generator.TypeResolver.Companion.containerName
import ru.alexey.event.threads.codegen.generator.TypeResolver.Companion.walk
import ru.alexey.event.threads.codegen.rules.CompiledRule
import ru.alexey.event.threads.codegen.rules.RuleCompiler
import ru.alexey.event.threads.codegen.rules.RuleEnvironment
import ru.alexey.event.threads.codegen.rules.RuleException
import ru.alexey.event.threads.codegen.rules.RuleParser
import com.squareup.kotlinpoet.CodeBlock
import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.EndpointModel
import ru.alexey.event.threads.codegen.model.RemoteApiModel
import ru.alexey.event.threads.codegen.model.HandlerModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel

/**
 * Where each kind of generated code goes, under the configured root package:
 * - [scopes]: `<Name>ScopeKey` + `<Name>Scope`;
 * - [events]: handler bases, one `<Event>Handler.kt` per input event;
 * - [containers]: `<State>Container`s;
 * - [models]: defined events, created types, params;
 * - [remote]: remote API interfaces and their Ktor implementations.
 */
class GeneratedPackages(root: String) {
    val scopes = "$root.scopes"
    val events = "$root.events"
    val containers = "$root.containers"
    val models = "$root.models"
    val remote = "$root.remote"
}

/**
 * Everything the generators share, derived once from all specs. Containers and handlers are
 * package-level, not per scope, so they're identified by *what* they are:
 * - a container per distinct state type across all scopes - two scopes holding `List<Todo>` share
 *   `TodoListContainer` (each scope instance still gets its own instance from DI);
 * - a handler base per distinct handler - the same handler declared in two scopes is one base.
 * Two different things that would get the same name are rejected rather than one silently winning.
 */
internal class GeneratorContext(
    root: String,
    val scopes: List<ScopeModel>,
    /** File comment of every generated file - with the versions it was generated from. */
    val header: String = GENERATED,
    /** The document's remote APIs. */
    val remote: List<RemoteApiModel> = emptyList(),
    /** Generate Ktor implementations of HTTP APIs (and default the bindings to them). */
    val ktor: Boolean = true,
) {

    init {
        SchemaValidator.validate(scopes)
    }

    val packages = GeneratedPackages(root)
    val types = TypeResolver(packages.models)

    /** [from]: the states a derived container is computed from - `null` for one handlers write. */
    class Container(val property: String, val className: ClassName, val state: TypeName, val from: List<ArgDef>?)

    /** The parts of a [HandlerModel] its generated base depends on - equal means same base. */
    data class Handler(
        val className: ClassName,
        val event: ClassName,
        val output: HandlerOutput,
        val reads: List<ArgDef>,
        val privacy: Privacy,
        val override: Boolean,
        /** The schema `rule` - generates `<Name>Rule`, the bindings' default implementation. */
        val rule: String? = null,
    ) {
        val ruleClassName: ClassName get() = ClassName(className.packageName, className.simpleName + "Rule")
        /** A state processor's own state. */
        val ownState: ArgDef? get() = (output as? HandlerOutput.Data)?.type
        /** The container it writes - its own state, or a request's `into`. */
        val writes: ArgDef? get() = output.writes
        val request: HandlerOutput.Request? get() = output as? HandlerOutput.Request
        val onMismatch: OutputMismatchBehavior? get() = (output as? HandlerOutput.Event)?.onMismatch
    }

    val containers: Map<ArgDef, Container> = run {
        // Per state: how its container is made - written (null) or derived from what. It's one
        // container class for every scope holding that state, so all of them must agree.
        val shapes: List<Pair<ArgDef, List<ArgDef>?>> = scopes.flatMap { scope ->
            scope.handlers.mapNotNull { it.output.writes }.map { it to null } +
                scope.derived.map { it.state to it.from }
        }.distinct()
        shapes.groupBy { it.first }.forEach { (state, ways) ->
            require(ways.size == 1) {
                "${containerName(state)}Container is written by handlers in one scope and derived (or derived differently) in another"
            }
        }
        val states = shapes.map { it.first }
        // Names are derived from the state type, so two different states can collide - e.g. a full
        // `Todo` definition in one place and a bare `Todo` reference in another.
        val clashes = states.groupBy(::containerName).filterValues { it.size > 1 }
        require(clashes.isEmpty()) {
            "Different state types share a container name ${clashes.keys} - spell the same state identically everywhere it's used"
        }
        val from = shapes.toMap()
        states.associateWith { state ->
            val name = containerName(state)
            Container(name.replaceFirstChar(Char::lowercaseChar), ClassName(packages.containers, "${name}Container"), types.typeOf(state), from[state])
        }
    }

    fun container(state: ArgDef): Container = containers.getValue(state)

    /** A scope's containers in creation order: handler-written ones, then derived ones in
     * declaration order (each only reads containers created before it). */
    fun containersOf(scope: ScopeModel): List<Container> =
        (scope.handlers.mapNotNull { it.output.writes } + scope.derived.map { it.state })
            .distinct()
            .map(::container)

    fun scopeKeyName(scope: ScopeModel) = "${scope.name.removeSuffix("Scope")}ScopeKey"

    /** A `dependsOn` entry's key object: a spec scope's generated key, or (qualified name) an
     * existing `ScopeKey` object. */
    fun dependencyKey(from: ScopeModel, name: String): ClassName {
        if ('.' in name) return ClassName.bestGuess(name)
        val target = requireNotNull(scopes.find { it.name == name }) {
            "Scope '${from.name}' dependsOn '$name', which no spec declares - use a qualified name for a ScopeKey defined elsewhere"
        }
        // dependsOn loads by key alone; this would otherwise only fail once someone loads it.
        require(target.params == null) {
            "Scope '${from.name}' dependsOn '$name', which needs params - dependsOn can't supply them"
        }
        return ClassName(packages.scopes, scopeKeyName(target))
    }

    fun handlerOf(model: HandlerModel): Handler = Handler(
        className = ClassName(packages.events, handlerName(model)),
        event = types.eventType(model.input),
        output = model.output,
        reads = model.reads,
        privacy = model.privacy,
        override = model.override,
        rule = model.rule,
    )

    val handlers: List<Handler> = run {
        val all = scopes.flatMap { scope -> scope.handlers.map { scope.name to handlerOf(it) } }
        all.groupBy { it.second.className }.forEach { (className, uses) ->
            require(uses.map { it.second }.distinct().size == 1) {
                "Handler base ${className.simpleName} is defined differently in scopes ${uses.map { it.first }.distinct()} - " +
                    "same event and kind need the same state, reads and output, or give one of them a `name`"
            }
        }
        all.map { it.second }.distinct()
    }

    /**
     * Every type a rule can construct or name, by simple name: events (inputs and outputs),
     * created types, and qualified type references anywhere in the schema. A simple name two
     * different types share maps to `null` - ambiguous, reported if a rule uses it.
     */
    val ruleTypes: Map<String, ClassName?> = run {
        val events = scopes.flatMap { scope ->
            scope.handlers.flatMap { h -> listOf(h.input) + h.output.cascades }
        }
        val args = scopes.flatMap { scope ->
            listOfNotNull(scope.params) + scope.derived.flatMap { listOf(it.state) + it.from } + scope.handlers.flatMap { h ->
                h.input.args + h.reads + listOfNotNull(h.output.writes) + h.output.cascades.flatMap { it.args }
            }
        } + remote.flatMap { api -> api.endpoints.flatMap { it.args + listOfNotNull(it.response) } }
        val walked = args.flatMap { it.walk() }
        val classes = events.map(types::eventType) + walked.mapNotNull { arg ->
            when {
                arg is ArgDef.Created -> ClassName(packages.models, arg.name)
                arg is ArgDef.Primitive && '.' in arg.typeKey -> ClassName.bestGuess(arg.typeKey)
                arg is ArgDef.Generics && '.' in arg.baseClassName -> ClassName.bestGuess(arg.baseClassName)
                else -> null
            }
        }
        classes.distinct().groupBy { it.simpleName }.mapValues { (_, c) -> c.singleOrNull() }
    }

    /** The scopes a handler (base) is declared in. */
    fun scopesOf(handler: Handler): List<ScopeModel> = scopes.filter { s -> s.handlers.any { handlerOf(it) == handler } }

    /** A rule's params type - the one params type of every scope it's declared in; `null` if it
     * doesn't use params. */
    fun ruleParams(handler: Handler): TypeName? =
        if (rules[handler]?.usesParams == true || requests[handler]?.usesParams == true) scopesOf(handler).first().params?.let(types::typeOf) else null

    /** Initial values per (scope, container property): the compiled expression. */
    val initials: Map<Pair<String, String>, CompiledRule> = run {
        val errors = mutableListOf<String>()
        val compiled = scopes.flatMap { scope ->
            val writable = scope.handlers.mapNotNull { (it.output as? HandlerOutput.Data)?.type }.map { container(it).property }.toSet()
            val requested = scope.handlers.mapNotNull { (it.output as? HandlerOutput.Request)?.into }.map { container(it).property }.toSet()
            val derived = scope.derived.map { container(it.state).property }.toSet()
            scope.initial.mapNotNull { (name, rule) ->
                when (name) {
                    in derived -> { errors += "Scope '${scope.name}' initial $name: a derived container's state comes from its transforms - it has no initial value"; null }
                    in requested -> { errors += "Scope '${scope.name}' initial $name: a request's container starts as RemoteData.Idle - it has no initial value"; null }
                    !in writable -> { errors += "Scope '${scope.name}' initial $name: no such container - the scope's: ${writable.sorted().joinToString()}"; null }
                    else -> try {
                        val env = RuleEnvironment(
                            eventParameter = null, stateNames = emptySet(), reads = emptyMap(),
                            params = scope.params != null, types = ruleTypes, outputs = null,
                        )
                        (scope.name to name) to RuleCompiler(env).compileExpression(RuleParser.parse(rule))
                    } catch (e: RuleException) {
                        errors += "Scope '${scope.name}' initial $name: ${e.message}"
                        null
                    }
                }
            }
        }.toMap()
        require(errors.isEmpty()) { "Initial value problems:\n" + errors.joinToString("\n") { " - $it" } }
        // A request's container starts Idle - nothing to write for it either.
        val idle = scopes.flatMap { scope ->
            scope.handlers.mapNotNull { (it.output as? HandlerOutput.Request)?.into }.distinct().map { into ->
                (scope.name to container(into).property) to CompiledRule(CodeBlock.of("%T.Idle", CoreSymbols.RemoteData), usesParams = false)
            }
        }
        compiled + idle
    }

    /** Containers some scope gives an initial value - each gets a `<State>ContainerInitial`. */
    fun hasInitial(container: Container): Boolean = initials.keys.any { it.second == container.property }

    fun initialClass(container: Container): ClassName =
        ClassName(container.className.packageName, container.className.simpleName + "Initial")

    /** Compiled rules of every handler that has one. */
    val rules: Map<Handler, CompiledRule> = run {
        val errors = mutableListOf<String>()
        val compiled = handlers.filter { it.rule != null }.mapNotNull { handler ->
            try {
                handler to compileRule(handler)
            } catch (e: RuleException) {
                errors += "${handler.className.simpleName} rule: ${e.message}"
                null
            }
        }.toMap()
        require(errors.isEmpty()) { "Rule problems:\n" + errors.joinToString("\n") { " - $it" } }
        compiled
    }

    private fun compileRule(handler: Handler): CompiledRule {
        val own = handler.ownState?.let(::container)
        val declaredIn = scopesOf(handler)
        val env = RuleEnvironment(
            eventParameter = if (own != null) "event" else "input",
            stateNames = if (own != null) setOf("state", own.property) else emptySet(),
            reads = handler.reads.map(::container).associate { it.property to it.property },
            params = declaredIn.all { it.params != null },
            types = ruleTypes,
            outputs = (handler.output as? HandlerOutput.Event)?.let { setOf(it.event.simpleName) },
        )
        val compiled = RuleCompiler(env).compile(RuleParser.parse(handler.rule!!))
        if (compiled.usesParams) {
            val distinct = declaredIn.mapNotNull { it.params }.map(types::typeOf).distinct()
            if (distinct.size > 1) {
                throw RuleException(handler.rule, handler.rule.indexOf("params").coerceAtLeast(0),
                    "uses params, but scopes ${declaredIn.map { it.name }} declaring this handler have different params types")
            }
        }
        return compiled
    }

    private fun handlerName(handler: HandlerModel) = handler.name ?: handler.input.simpleName + when (handler.output) {
        is HandlerOutput.Data -> "StateProcessor"
        is HandlerOutput.Event -> "EventCascader"
        is HandlerOutput.Request -> "Request"
        HandlerOutput.None -> "SideEffect"
    }

    // --- remote ----------------------------------------------------------------------------

    val apis: Map<String, RemoteApiModel> = remote.associateBy { it.name }

    fun apiClass(api: RemoteApiModel): ClassName = ClassName(packages.remote, "${api.name}RemoteApi")
    fun ktorClass(api: RemoteApiModel): ClassName = ClassName(packages.remote, "${api.name}RemoteApiKtor")
    fun apiProperty(api: RemoteApiModel): String = api.name.replaceFirstChar(Char::lowercaseChar) + "RemoteApi"

    /** The API and endpoint a request calls. */
    fun endpointOf(request: HandlerOutput.Request): Pair<RemoteApiModel, EndpointModel> {
        val (apiName, endpointName) = request.call.split('.')
        val api = requireNotNull(apis[apiName]) { "request ${request.call}: no remote API '$apiName' - declared: ${apis.keys.sorted()}" }
        val endpoint = requireNotNull(api.endpoints.find { it.name == endpointName }) {
            "request ${request.call}: '$apiName' has no endpoint '$endpointName' - it has: ${api.endpoints.map { it.name }}"
        }
        return api to endpoint
    }

    /** The APIs a scope's requests call, in first-use order. */
    fun apisOf(scope: ScopeModel): List<RemoteApiModel> =
        scope.handlers.mapNotNull { (it.output as? HandlerOutput.Request)?.let(::endpointOf)?.first }.distinct()

    /** The endpoint's response type - `Unit` without one. */
    fun responseType(endpoint: EndpointModel): TypeName = endpoint.response?.let(types::typeOf) ?: com.squareup.kotlinpoet.UNIT

    /** A request's compiled `args` (in endpoint order), `onSuccess`, `onFailure`. */
    class CompiledRequest(
        val api: RemoteApiModel,
        val endpoint: EndpointModel,
        val args: List<Pair<String, CodeBlock>>,
        val onSuccess: CompiledRule?,
        val onFailure: CompiledRule?,
    ) {
        val usesParams: Boolean get() = onSuccess?.usesParams == true || onFailure?.usesParams == true || argsUseParams
        internal var argsUseParams = false
    }

    val requests: Map<Handler, CompiledRequest> = run {
        val errors = mutableListOf<String>()
        val compiled = handlers.mapNotNull { handler ->
            val request = handler.request ?: return@mapNotNull null
            try {
                handler to compileRequest(handler, request)
            } catch (e: IllegalArgumentException) {
                errors += "${handler.className.simpleName}: ${e.message}"
                null
            }
        }.toMap()
        require(errors.isEmpty()) { "Request problems:\n" + errors.joinToString("\n") { " - $it" } }
        compiled
    }

    private fun compileRequest(handler: Handler, request: HandlerOutput.Request): CompiledRequest {
        val (api, endpoint) = endpointOf(request)
        val response = responseType(endpoint)
        val into = types.typeOf((request.into as ArgDef.Generics).parameters.single())
        require(into == response) { "into is RemoteData<$into>, but ${request.call} responds $response" }
        val names = endpoint.args.map { it.fieldName!! }
        val missing = names - request.args.keys
        val unknown = request.args.keys - names.toSet()
        require(missing.isEmpty() && unknown.isEmpty()) {
            listOfNotNull(
                "args $missing of ${request.call} aren't given".takeIf { missing.isNotEmpty() },
                "${request.call} has no args $unknown".takeIf { unknown.isNotEmpty() },
            ).joinToString("; ")
        }
        val declaredIn = scopesOf(handler)
        fun env(outputs: Set<String>?, extra: Map<String, String>) = RuleEnvironment(
            eventParameter = "event",
            stateNames = emptySet(),
            reads = handler.reads.map(::container).associate { it.property to it.property },
            params = declaredIn.all { it.params != null },
            types = ruleTypes,
            outputs = outputs,
            extra = extra,
        )
        var argsUseParams = false
        val args = names.map { name ->
            val rule = request.args.getValue(name)
            val compiled = try {
                RuleCompiler(env(null, emptyMap())).compileExpression(RuleParser.parse(rule))
            } catch (e: RuleException) {
                throw IllegalArgumentException("arg $name: ${e.message}")
            }
            argsUseParams = argsUseParams || compiled.usesParams
            name to compiled.body
        }
        val emits = request.emits.map { it.simpleName }.toSet()
        fun clauses(rule: String?, what: String, extra: Map<String, String>) = rule?.let {
            try {
                RuleCompiler(env(emits, extra)).compile(RuleParser.parse(it))
            } catch (e: RuleException) {
                throw IllegalArgumentException("$what: ${e.message}")
            }
        }
        return CompiledRequest(
            api, endpoint, args,
            onSuccess = clauses(request.onSuccess, "onSuccess", mapOf("result" to "result")),
            onFailure = clauses(request.onFailure, "onFailure", mapOf("error" to "error")),
        ).also { it.argsUseParams = argsUseParams }
    }
}
