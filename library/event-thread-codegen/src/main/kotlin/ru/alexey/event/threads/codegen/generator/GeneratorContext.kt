package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import ru.alexey.event.threads.OutputMismatchBehavior
import ru.alexey.event.threads.Privacy
import ru.alexey.event.threads.codegen.generator.TypeResolver.Companion.containerName
import ru.alexey.event.threads.codegen.model.ArgDef
import ru.alexey.event.threads.codegen.model.HandlerModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel

/**
 * Where each kind of generated code goes, under the configured root package:
 * - [scopes]: `<Name>ScopeKey` + `<Name>Scope`;
 * - [events]: handler bases, one `<Event>Handler.kt` per input event;
 * - [containers]: `<State>Container`s;
 * - [models]: defined events, created types, params.
 */
class GeneratedPackages(root: String) {
    val scopes = "$root.scopes"
    val events = "$root.events"
    val containers = "$root.containers"
    val models = "$root.models"
}

/**
 * Everything the generators share, derived once from all specs. Containers and handlers are
 * package-level, not per scope, so they're identified by *what* they are:
 * - a container per distinct state type across all scopes - two scopes holding `List<Todo>` share
 *   `TodoListContainer` (each scope instance still gets its own instance from DI);
 * - a handler base per distinct handler - the same handler declared in two scopes is one base.
 * Two different things that would get the same name are rejected rather than one silently winning.
 */
internal class GeneratorContext(root: String, val scopes: List<ScopeModel>) {

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
    ) {
        val ownState: ArgDef? get() = (output as? HandlerOutput.Data)?.type
        val onMismatch: OutputMismatchBehavior? get() = (output as? HandlerOutput.Event)?.onMismatch
    }

    val containers: Map<ArgDef, Container> = run {
        // Per state: how its container is made - written (null) or derived from what. It's one
        // container class for every scope holding that state, so all of them must agree.
        val shapes: List<Pair<ArgDef, List<ArgDef>?>> = scopes.flatMap { scope ->
            scope.handlers.mapNotNull { (it.output as? HandlerOutput.Data)?.type }.map { it to null } +
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
        (scope.handlers.mapNotNull { (it.output as? HandlerOutput.Data)?.type } + scope.derived.map { it.state })
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

    private fun handlerName(handler: HandlerModel) = handler.name ?: handler.input.simpleName + when (handler.output) {
        is HandlerOutput.Data -> "StateProcessor"
        is HandlerOutput.Event -> "EventCascader"
        HandlerOutput.None -> "SideEffect"
    }
}
