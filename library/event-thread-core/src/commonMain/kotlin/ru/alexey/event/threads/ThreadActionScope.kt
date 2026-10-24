package ru.alexey.event.threads

import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.IEventBus
import ru.alexey.event.threads.bus.ValidatingEventBus

/**
 * The implicit receiver of a `.then { }`/`.then(container) { }`/`.end { }` action block - every
 * member other than [eventBus] resolves through the enclosing [Scope] exactly as before (Kotlin
 * falls through to it as the next-outer receiver, since this class doesn't redeclare them).
 * [eventBus] shadows [Scope.eventBus] with a version that enforces this thread's declared
 * `output<T>()` set ([EventThreadMetadataBuilder.output]/[DeclaredOutputs]), when one was
 * declared - or is a plain, zero-overhead passthrough to the real bus when it wasn't (the
 * overwhelming majority of threads), so this costs nothing for a thread that never opts in.
 *
 * Not constructed directly - built fresh for every single dispatch, inside [Scope]'s `.then`/
 * `.end` builder extensions, not once at registration time and reused: this bus's dispatch loop
 * can run more than one event through the *same* registered action concurrently (see
 * [ru.alexey.event.threads.bus.EventBus]'s KDoc), so [triggeringEvent] - needed to report a
 * [OutputMismatchBehavior.Warning] against the right event - can't safely be shared mutable state
 * on a reused instance.
 */
class ThreadActionScope(
    scope: Scope,
    threadDescription: String,
    triggeringEvent: Event,
    declaredOutputs: DeclaredOutputs?,
) {
    val eventBus: IEventBus = if (declaredOutputs == null) {
        scope.eventBus
    } else {
        ValidatingEventBus(scope.eventBus, threadDescription, triggeringEvent, declaredOutputs)
    }
}
