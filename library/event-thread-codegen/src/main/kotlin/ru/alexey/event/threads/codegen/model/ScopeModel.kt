package ru.alexey.event.threads.codegen.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import ru.alexey.event.threads.OutputMismatchBehavior
import ru.alexey.event.threads.Privacy
import ru.alexey.event.threads.codegen.rules.RuleException
import ru.alexey.event.threads.codegen.rules.RuleParser

/**
 * Source-agnostic description of one scope to generate - what
 * [ru.alexey.event.threads.codegen.generator.ScopeGenerator] consumes. The Gradle DSL
 * ([ru.alexey.event.threads.codegen.ScopeDeclaration]) is just one way to produce it; a JSON spec
 * file (see [ScopeSpecFormat]) maps onto the same model without touching the generator.
 *
 * Validation lives in `init` blocks, so it runs for deserialized specs as well as hand-built ones.
 */
@Serializable
data class ScopeModel(
    /** Scope name as declared, e.g. `Todo` - becomes `AbstractTodoScope`. */
    val name: String,
    val description: String? = null,
    val handlers: List<HandlerModel> = emptyList(),
    /** Load-time params - the `P` of the generated `ScopeKey<P>`; absent means `Unit`. Prefer a
     * [ArgDef.Created] with fields: it's generated as a `data class`, and structural equality is
     * what lets params tell scope instances apart (a scope per list, per item). */
    val params: ArgDef? = null,
    /** Scopes loading this one also loads (`ScopeKey.extend { dependsOn(...) }`): a spec scope's
     * name, or the qualified name of an existing `ScopeKey` object. A scope with params can't be
     * depended on - dependsOn loads by key alone. */
    val dependsOn: List<String> = emptyList(),
    /** Events routed into this scope when another scope in the holder dispatches them
     * (`consume<E>()`), by the name of the handler input that handles them here. */
    val consumes: List<String> = emptyList(),
    /** Containers computed from other containers of this scope (`transform`s) rather than
     * written by a handler - e.g. a filtered view of a list. */
    val derived: List<DerivedModel> = emptyList(),
    /** Initial state per container (by container name), in the rule language - `"[]"`, `"true"`,
     * `"'Work'"`, `"params.initialText"`. Generates the container binding's default (a
     * `<State>ContainerInitial`); a binding of your own still overrides it. Not for derived
     * containers - their state comes from `transforms`. */
    val initial: Map<String, String> = emptyMap(),
) {
    init {
        requireIdentifier(name, "Scope name")
        // Several handlers per event are fine (they run in declaration order); several with the
        // same generated name aren't - checked where names are resolved, in the generator.
        dependsOn.forEach { requireTypeName(it, "Scope '$name' dependsOn") }
        // A routed event with no handler here would arrive and do nothing - almost certainly a
        // typo or a removed handler, never intended.
        val handled = handlers.map { it.input.name }.toSet()
        val unhandled = consumes.filterNot { it in handled }
        require(unhandled.isEmpty()) { "Scope '$name' consumes $unhandled but has no handler for it" }
        // A scope's containers are exactly its distinct `data` output states - a read naming
        // anything else would generate a snapshot of a container that doesn't exist.
        val written = handlers.mapNotNull { it.output.writes }.toSet()
        val known = written.toMutableSet()
        derived.forEach { d ->
            require(d.state !in written) { "Scope '$name': ${d.state} is written by a handler, so it can't also be derived" }
            val unknown = d.from.filterNot { it in known }
            require(unknown.isEmpty()) {
                "Scope '$name': derived ${d.state.fieldName ?: d.state} is computed from $unknown - not a container declared before it"
            }
            known += d.state
        }
        initial.forEach { (container, rule) ->
            try {
                RuleParser.parse(rule)
            } catch (e: RuleException) {
                throw IllegalArgumentException("Scope '$name' initial $container: ${e.message}", e)
            }
        }
        handlers.forEach { handler ->
            val unknown = handler.reads.filterNot { it in known }
            require(unknown.isEmpty()) {
                "Scope '$name': ${handler.input.name} reads $unknown, which is no container of the scope"
            }
        }
    }
}

/**
 * One handler on a scope: reacts to [input] and produces [output]. Which
 * `ru.alexey.event.threads.scopeholder.typed.EventHandler` variant it becomes follows from
 * [output] - see [HandlerOutput].
 *
 * Only registration metadata that can't be derived lives here: the action type and the declared
 * outputs follow from [output], the thread description from the generated handler's class name.
 */
@Serializable
data class HandlerModel(
    val input: EventModel,
    val output: HandlerOutput = HandlerOutput.None,
    /** `EventHandler.privacy` - informational, as `thread<T> { privacy(...) }`. */
    val privacy: Privacy = Privacy.public,
    /** `EventHandler.override` - replace, rather than join, another registration for [input]. */
    val override: Boolean = false,
    /** Generated base class name. Defaults to `<Event><Kind>` (`AddTodoStateProcessor`); needed
     * only where two handlers would get the same default - several of one kind for one event. */
    val name: String? = null,
    /** Containers this handler reads but doesn't update, by state type (a `data` output type in
     * the same scope) - each becomes a `ContainerSnapshot` in the generated base's `Deps`. */
    val reads: List<ArgDef> = emptyList(),
    /** Input -> output examples, each generated as a test of the implementation (see
     * [ExampleModel]). Not for `none` outputs - a side effect has nothing to check. */
    val examples: List<ExampleModel> = emptyList(),
    /** The handler's behaviour in the rule language (see SCHEMA_GUIDE.md, "Rules") - generates
     * `<Name>Rule`, the bindings' default implementation; a binding of your own still overrides
     * it. A state handler's rule is the new state (`state | where it.id != event.id`), a
     * cascading one's the events (`when totalAdded >= 5 emit AddLimitReached(totalAdded)`). */
    val rule: String? = null,
) {
    init {
        name?.let { requireIdentifier(it, "Handler name") }
        rule?.let {
            require(output != HandlerOutput.None) { "${input.name}: a side effect can't have a rule - it has no output to compute" }
            require(output !is HandlerOutput.Request) { "${input.name}: a request is generated from its `args`/`onSuccess`/`onFailure` - it has no `rule`" }
            try {
                RuleParser.parse(it)
            } catch (e: RuleException) {
                throw IllegalArgumentException("${input.name} rule: ${e.message}", e)
            }
        }
        examples.forEachIndexed { i, example ->
            val label = "${input.name} example ${example.name ?: "#$i"}"
            when (output) {
                is HandlerOutput.Data -> require(example.given != null && example.expect != null) {
                    "$label: a state handler's example needs `given` and `expect`"
                }
                is HandlerOutput.Event -> require(example.emits != null) {
                    "$label: a cascading handler's example needs `emits`"
                }
                is HandlerOutput.Request -> require((example.respond != null) != (example.fail != null)) {
                    "$label: a request's example needs `respond` (the response) or `fail` (an error message)"
                }
                HandlerOutput.None -> throw IllegalArgumentException("$label: a side effect has no output to check")
            }
            val unknownReads = example.reads.keys - reads.map { it.fieldName ?: "" }.toSet()
            require(unknownReads.isEmpty()) { "$label: `reads` $unknownReads aren't containers this handler reads" }
        }
    }
}

/**
 * One example of a handler: the state it starts from, the event, what must come out. Values are
 * JSON, turned into Kotlin at generation (`{"id": 1, "text": "a"}` for a `Todo` becomes
 * `Todo(id = 1, text = "a")` - constructor parameter names must match the keys).
 */
@Serializable
data class ExampleModel(
    /** Test name suffix; defaults to its index. */
    val name: String? = null,
    /** The handler's own state before the event (state handlers). */
    val given: JsonElement? = null,
    /** State of each container the handler reads, by container name. */
    val reads: Map<String, JsonElement> = emptyMap(),
    /** The event's constructor arguments; omitted for an `object` event. */
    val event: JsonElement? = null,
    /** The handler's own state after the event (state handlers). */
    val expect: JsonElement? = null,
    /** The events it cascades, in order - each the arguments of the declared output event (for
     * a request with several `emits`: `{ "<Event>": { args } }`). */
    val emits: List<JsonElement>? = null,
    /** Request: what the endpoint responds (its response's JSON). */
    val respond: JsonElement? = null,
    /** Request: the endpoint fails with this message. */
    val fail: String? = null,
) {
    init {
        name?.let { requireIdentifier(it, "Example name") }
    }
}

/** A container whose [state] is computed from the containers holding [from] (each a state type
 * declared in the same scope - written by a handler, or derived earlier). */
@Serializable
data class DerivedModel(
    val state: ArgDef,
    val from: List<ArgDef>,
) {
    init {
        require(from.isNotEmpty()) { "Derived ${state.fieldName ?: state} needs at least one source" }
    }
}

/**
 * An event type. A simple [name] (`AddTodo`) is a definition: generated as a class implementing
 * core's `Event`, one constructor property per [args] entry. A qualified [name]
 * (`org.company.sample.AddTodo`) references an existing event class instead - its shape is its
 * own, so it takes no [args].
 */
@Serializable
data class EventModel(
    val name: String,
    val args: List<ArgDef> = emptyList(),
) {
    init {
        requireTypeName(name, "Event name")
        require(!isReference || args.isEmpty()) { "Event '$name' references an existing class - it can't declare args" }
    }

    val isReference: Boolean get() = '.' in name

    val simpleName: String get() = name.substringAfterLast('.')
}

/** What a [HandlerOutput] produces - and so which `EventHandler` variant it maps onto. */
@Serializable
sealed class HandlerOutput {

    /** The state it writes - its container: a `data` type, a `request`'s `into`. */
    open val writes: ArgDef? get() = null

    /** The events it may cascade: an `event`'s event, a `request`'s `emits`. */
    open val cascades: List<EventModel> get() = emptyList()

    /** New state of type [type] -> `EventHandler.StateProcessor` over a `Datacontainer` of it. */
    @Serializable
    @SerialName("data")
    data class Data(val type: ArgDef) : HandlerOutput() {
        override val writes: ArgDef get() = type
    }

    /** Cascades [event] onto the bus -> `EventHandler.EventCascader`, with `outputs =
     * setOf(<event>::class)`; [onMismatch] is what happens if it returns anything else. */
    @Serializable
    @SerialName("event")
    data class Event(
        val event: EventModel,
        val onMismatch: OutputMismatchBehavior = OutputMismatchBehavior.Strict,
    ) : HandlerOutput() {
        override val cascades: List<EventModel> get() = listOf(event)
    }

    /**
     * Calls [call] (`"<Api>.<endpoint>"` of the document's `remote`) and tracks it in the
     * `RemoteData` container [into] -> `EventHandler.Request`. [args]: the endpoint's args, each a
     * rule expression (`event`, `params`, reads); [onSuccess]/[onFailure]: cascading rules over
     * `result`/`error`, emitting [emits] events. Fully generated - the developer writes nothing,
     * but can bind an implementation of their own.
     */
    @Serializable
    @SerialName("request")
    data class Request(
        val call: String,
        /** `RemoteData<R>` of the endpoint's response `R`:
         * `{ "kind": "generics", "baseClassName": "RemoteData", "field": "todos", "parameters": [ <R> ] }`. */
        val into: ArgDef,
        val args: Map<String, String> = emptyMap(),
        val emits: List<EventModel> = emptyList(),
        val onSuccess: String? = null,
        val onFailure: String? = null,
        val policy: RequestPolicyModel = RequestPolicyModel(),
        val onMismatch: OutputMismatchBehavior = OutputMismatchBehavior.Strict,
    ) : HandlerOutput() {
        override val writes: ArgDef get() = into
        override val cascades: List<EventModel> get() = emits

        init {
            require(Regex("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*").matches(call)) {
                "request call '$call' isn't `<Api>.<endpoint>`"
            }
            require(into is ArgDef.Generics && into.baseClassName.substringAfterLast('.') == "RemoteData" && into.parameters.size == 1) {
                "request into must be RemoteData<response>: { \"kind\": \"generics\", \"baseClassName\": \"RemoteData\", \"field\": ..., \"parameters\": [ <response> ] }"
            }
            require(emits.isNotEmpty() || (onSuccess == null && onFailure == null)) {
                "request $call: onSuccess/onFailure emit events - declare them in `emits`"
            }
            listOfNotNull(onSuccess, onFailure).forEach {
                try {
                    RuleParser.parse(it)
                } catch (e: RuleException) {
                    throw IllegalArgumentException("request $call: ${e.message}", e)
                }
            }
            args.values.forEach {
                try {
                    RuleParser.parse(it)
                } catch (e: RuleException) {
                    throw IllegalArgumentException("request $call args: ${e.message}", e)
                }
            }
        }
    }

    /** Nothing observable -> `EventHandler.SideEffect`. */
    @Serializable
    @SerialName("none")
    data object None : HandlerOutput()
}
