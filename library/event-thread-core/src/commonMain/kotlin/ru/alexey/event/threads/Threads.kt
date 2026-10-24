package ru.alexey.event.threads

import kotlinx.serialization.Serializable
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.utils.Builder
import kotlin.reflect.KClass

/**
 * A registered handler for events of type [T] on some [ru.alexey.event.threads.Scope]'s bus -
 * returned by [ru.alexey.event.threads.Scope.thread] and extended with `.then { }`/`.end { }`
 * (see [ru.alexey.event.threads.Scope]). Not constructed directly; [EventThreadMetadataBuilder]
 * builds it from the `thread<T> { }` config block.
 */
open class EventThread<T: Event>(
    private val metadata: EventMetadata,
    val declaredOutputs: DeclaredOutputs? = null,
) {
    private val eventThreadActions: MutableList<EventThreadAction<T>> = mutableListOf()

    val actions: List<suspend (Event) -> Unit>
        get() = eventThreadActions.map { it.action as? suspend (Event) -> Unit ?: throw IllegalStateException("Action casting failed") }

    @Suppress("UNCHECKED_CAST")
    val rawActions: List<EventThreadAction<Event>>
        get() = eventThreadActions as List<EventThreadAction<Event>>

    /** Introspection snapshot of this thread's declared metadata and each chained action, in
     * order - see [EventThreadActionInfo]. */
    val eventMetadatas: EventThreadInfo
        get() = EventThreadInfo(metadata, eventThreadActions.map {
            EventThreadActionInfo(it.type, it.producedType?.simpleName)
        })

    /** Appends [eventThreadAction] to this thread's action chain - the mechanism behind `.then {
     * }`/`.end { }`. */
    operator fun invoke(eventThreadAction: EventThreadAction<T>) {
        eventThreadActions.add(
            eventThreadAction
        )
    }

    /** Appends every action in [actions] to this thread's chain - used when merging a
     * later/inherited registration for the same event type onto this one. */
    operator fun plus(actions: List<EventThreadAction<Event>>) {
        eventThreadActions.addAll(actions.map { EventThreadAction(it.action, it.type, it.producedType) })
    }
}

/** Introspection snapshot of one [EventThread]: its declared [metadata] and each chained action,
 * in order - see [EventThread.eventMetadatas]/[ru.alexey.event.threads.ScopeMetadata]. */
@Serializable
data class EventThreadInfo(
    val metadata: EventMetadata,
    val actions: List<EventThreadActionInfo>
) {
    /** The distinct event types this thread may dispatch as a consequence of handling its
     * subscribed event, gathered from every `cascade` step's non-null
     * [EventThreadActionInfo.producedType]. */
    val producedTypes: Set<String>
        get() = actions.filter { it.type == EventType.cascade }.mapNotNull { it.producedType }.toSet()
}

/** One chained step's [type], plus - for a `cascade` step - the simple name of the event class
 * its `.then { }` factory produces, captured from the `reified` return type at the call site.
 * `null` for every non-`cascade` step.
 *
 * When a `.then { }` factory returns different event subtypes on different branches, Kotlin
 * infers the call site's `reified` type as their common supertype, not each branch's leaf type -
 * this field then holds that supertype's name rather than failing or going `null`. A tool
 * building an event-flow graph from this data needs to compare [producedType] against the
 * [ru.alexey.event.threads.bus.Event] hierarchy to tell an exact leaf type from a broad
 * supertype that needs manual refinement. */
@Serializable
data class EventThreadActionInfo(
    val type: EventType,
    val producedType: String? = null,
)

/** What a chained action does to the event pipeline - `consume` ([ru.alexey.event.threads.Scope.
 * end]), `cascade` ([ru.alexey.event.threads.Scope.then] dispatching a new event), `modification`
 * ([ru.alexey.event.threads.Scope.then] updating a [ru.alexey.event.threads.datacontainer.
 * Datacontainer]), plus `process`/`external` for other bookkeeping uses. */
enum class EventType {
    consume, cascade, process, modification, external
}

/** Declared visibility of an [EventThread] - currently descriptive metadata only; doesn't yet
 * restrict who can dispatch to or observe the thread. */
enum class Privacy {
    private, public
}

/** One chained step on an [EventThread]: [action] to run, tagged with its [type] for
 * introspection, plus - for a `cascade` step - the [KClass] of the event its `.then { }` factory
 * produces ([producedType], `null` for every other step type). Built via `.then { }`/`.end { }`,
 * not directly. */
class EventThreadAction<T: Event>(
    val action: suspend (T) -> Unit,
    val type: EventType,
    val producedType: KClass<out Event>? = null,
)

/** Declared metadata for an [EventThread] - [description] and [privacy] are purely informational;
 * [override] controls whether a second `thread<T>()` registration for the same event type on a
 * live scope replaces the first or is merged alongside it (see [ru.alexey.event.threads.bus.
 * EventBus]'s subscriber registration). [allowedOutputs] is the introspection (simple-name-string)
 * view of [EventThreadMetadataBuilder.output] - see [DeclaredOutputs] for the enforced,
 * [kotlin.reflect.KClass]-based form actually used at runtime. */
@Serializable
data class EventMetadata(
    val description: String,
    val privacy: Privacy = Privacy.public,
    val override: Boolean = false,
    val allowedOutputs: Set<String> = emptySet(),
)

/**
 * How a push that doesn't match any of an [EventThread]'s declared [DeclaredOutputs.allowedTypes]
 * is handled - set via `thread<T> { onMismatch(...) }` (see [EventThreadMetadataBuilder.
 * onMismatch]). Only meaningful once at least one [EventThreadMetadataBuilder.output] has been
 * declared; with none declared there's nothing to mismatch against, so every push is allowed
 * regardless of this setting.
 */
enum class OutputMismatchBehavior {
    /** Throws [ru.alexey.event.threads.bus.OutputContractViolationException] synchronously from
     * the offending `eventBus += ` call - the event is never delivered. The default. */
    Strict,

    /** Reports the violation through this bus's own `onError` [ru.alexey.event.threads.bus.
     * ErrorHandler]s (the same mechanism a thrown action/watcher already goes through - see
     * [ru.alexey.event.threads.bus.EventBus]) instead of throwing - the event still isn't
     * delivered, but the caller doesn't crash. */
    Warning,

    /** Drops the event with no reaction at all - not thrown, not reported, not delivered. */
    Silent,
}

/**
 * The enforced form of a `thread<T> { output<X>(); output<Y>() }` declaration - see
 * [EventThreadMetadataBuilder.output]. Carried on [EventThread.declaredOutputs] (`null` when
 * nothing was declared, meaning no restriction at all) and consulted by [ru.alexey.event.threads.
 * ThreadActionScope]'s wrapped `eventBus` to validate every push this thread's own action makes -
 * a cascading `.then { }`'s return value included, not just an explicit imperative `eventBus +=`.
 */
data class DeclaredOutputs(
    val allowedTypes: Set<KClass<out Event>>,
    val onMismatch: OutputMismatchBehavior,
)

/** Receiver for `thread<T> { }` (see [ru.alexey.event.threads.Scope.thread]) - configures an
 * [EventThread]'s [EventMetadata] before it's registered. */
class EventThreadMetadataBuilder<T: Event>(
    private var description: String = "",
    private var privacy: Privacy = Privacy.public,
    private var override: Boolean = false,
): Builder<EventThread<T>> {
    private val allowedOutputs = mutableSetOf<KClass<out Event>>()
    private var onMismatch: OutputMismatchBehavior = OutputMismatchBehavior.Strict

    override fun build(): EventThread<T> {
        val declaredOutputs = if (allowedOutputs.isEmpty()) null else DeclaredOutputs(allowedOutputs.toSet(), onMismatch)
        return EventThread<T>(
            EventMetadata(
                description, privacy, override,
                allowedOutputs.mapTo(mutableSetOf()) { it.simpleName.orEmpty() }
            ),
            declaredOutputs
        )
    }

    /** Sets this thread's [EventMetadata.description] - informational only. */
    fun description(block: () -> String) {
        description = block()
    }

    /** Sets this thread's [EventMetadata.privacy] - informational only. */
    fun privacy(privacy: Privacy) {
        this.privacy = privacy
    }

    /** When `true`, registering this thread replaces any existing `thread<T>()` registration for
     * the same event type on the scope instead of merging alongside it as an additional
     * subscriber - see [EventMetadata.override]. */
    fun override(override: Boolean) { this.override = override }

    /** Declares [O] as an event this thread may legitimately push onto its own `eventBus` - from
     * a cascading `.then { }`'s return value, or an imperative `eventBus += ` call inside any of
     * this thread's own `.then { }`/`.end { }` bodies. Call more than once to declare several.
     * Undeclared (the default): no restriction at all, every push is allowed - exactly the
     * behavior before this existed. See [onMismatch] for what happens to a push that isn't one of
     * the declared types once at least one has been. */
    inline fun <reified O : Event> output() { output(O::class) }

    /** [output] for one or more [KClass]es directly, when a reified type parameter isn't
     * convenient at the call site. */
    fun output(vararg types: KClass<out Event>) { allowedOutputs += types }

    /** Sets [DeclaredOutputs.onMismatch] - see [OutputMismatchBehavior]. Defaults to
     * [OutputMismatchBehavior.Strict]; has no effect unless at least one [output] was declared. */
    fun onMismatch(behavior: OutputMismatchBehavior) { this.onMismatch = behavior }
}

class EventThreadActionBuilder<T: Event>(
    private val type: EventType,
    private val producedType: KClass<out Event>? = null,
    private val action: suspend (T) -> Unit
): Builder<EventThreadAction<T>> {
    override fun build(): EventThreadAction<T> {
        return EventThreadAction(action, type, producedType)
    }
}







