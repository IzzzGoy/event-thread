package ru.alexey.event.threads

import kotlinx.serialization.Serializable
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.utils.Builder

/**
 * A registered handler for events of type [T] on some [ru.alexey.event.threads.Scope]'s bus -
 * returned by [ru.alexey.event.threads.Scope.thread] and extended with `.then { }`/`.end { }`
 * (see [ru.alexey.event.threads.Scope]). Not constructed directly; [EventThreadMetadataBuilder]
 * builds it from the `thread<T> { }` config block.
 */
open class EventThread<T: Event>(
    private val metadata: EventMetadata
) {
    private val eventThreadActions: MutableList<EventThreadAction<T>> = mutableListOf()

    val actions: List<suspend (Event) -> Unit>
        get() = eventThreadActions.map { it.action as? suspend (Event) -> Unit ?: throw IllegalStateException("Action casting failed") }

    @Suppress("UNCHECKED_CAST")
    val rawActions: List<EventThreadAction<Event>>
        get() = eventThreadActions as List<EventThreadAction<Event>>

    /** Introspection snapshot of this thread's declared metadata and the [EventType] of each
     * chained action, in order. */
    val eventMetadatas: EventThreadInfo
        get() = EventThreadInfo(metadata, eventThreadActions.map { it.type })

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
        eventThreadActions.addAll(actions.map { EventThreadAction(it.action, it.type) })
    }
}

/** Introspection snapshot of one [EventThread]: its declared [metadata] and the [EventType] of
 * each chained action, in order - see [EventThread.eventMetadatas]/[ru.alexey.event.threads.
 * ScopeMetadata]. */
@Serializable
data class EventThreadInfo(
    val metadata: EventMetadata,
    val actions: List<EventType>
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
 * introspection. Built via `.then { }`/`.end { }`, not directly. */
class EventThreadAction<T: Event>(
    val action: suspend (T) -> Unit,
    val type: EventType
)

/** Declared metadata for an [EventThread] - [description] and [privacy] are purely informational;
 * [override] controls whether a second `thread<T>()` registration for the same event type on a
 * live scope replaces the first or is merged alongside it (see [ru.alexey.event.threads.bus.
 * EventBus]'s subscriber registration). */
@Serializable
data class EventMetadata(
    val description: String,
    val privacy: Privacy = Privacy.public,
    val override: Boolean = false,
)

/** Receiver for `thread<T> { }` (see [ru.alexey.event.threads.Scope.thread]) - configures an
 * [EventThread]'s [EventMetadata] before it's registered. */
class EventThreadMetadataBuilder<T: Event>(
    private var description: String = "",
    private var privacy: Privacy = Privacy.public,
    private var override: Boolean = false,
): Builder<EventThread<T>> {

    override fun build(): EventThread<T> {
        return EventThread<T>(
            EventMetadata(
                description, privacy, override
            )
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
}

class EventThreadActionBuilder<T: Event>(
    private val type: EventType,
    private val action: suspend (T) -> Unit
): Builder<EventThreadAction<T>> {
    override fun build(): EventThreadAction<T> {
        return EventThreadAction(action, type)
    }
}







