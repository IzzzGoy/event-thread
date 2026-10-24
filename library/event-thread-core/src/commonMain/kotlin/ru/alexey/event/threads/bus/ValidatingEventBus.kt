package ru.alexey.event.threads.bus

import ru.alexey.event.threads.DeclaredOutputs
import ru.alexey.event.threads.OutputMismatchBehavior

/** Thrown by [ValidatingEventBus] under [OutputMismatchBehavior.Strict] (the default) - [event]'s
 * type isn't one of [allowed], the set [declaredThread] declared via `thread<T> { output<X>() }`. */
class OutputContractViolationException(
    val event: Event,
    val declaredThread: String,
    val allowed: Set<kotlin.reflect.KClass<out Event>>,
) : IllegalStateException(
    "Thread '$declaredThread' pushed ${event::class.simpleName} onto its own eventBus, but only " +
        "declared output<>() for ${allowed.mapNotNull { it.simpleName }} - add " +
        "output<${event::class.simpleName}>() to that thread<${declaredThread}>() if this is " +
        "intentional, or fix the handler if it isn't."
)

/**
 * Wraps [real] so every push through it is checked against [declaredOutputs] before being let
 * through - see [ru.alexey.event.threads.ThreadActionScope], which is the only place this is
 * constructed. A mismatch is never delivered to [real] under any [OutputMismatchBehavior] - the
 * three behaviors differ only in how loudly the rejection is reported, not in whether the event
 * still goes through.
 *
 * Everything except [plusAssign] delegates straight to [real] - `output`/`metadata`/
 * `collectToEventBus` aren't about *this* thread's own emissions, so there's nothing to enforce
 * there.
 */
internal class ValidatingEventBus(
    private val real: EventBus,
    private val threadDescription: String,
    private val triggeringEvent: Event,
    private val declaredOutputs: DeclaredOutputs,
) : IEventBus by real {

    override fun plusAssign(event: Event) {
        if (declaredOutputs.allowedTypes.any { it.isInstance(event) }) {
            real += event
            return
        }

        val violation = OutputContractViolationException(event, threadDescription, declaredOutputs.allowedTypes)
        when (declaredOutputs.onMismatch) {
            OutputMismatchBehavior.Strict -> throw violation
            // Reported against triggeringEvent, not the rejected `event` itself - notifyError's
            // "event" means "the event whose processing this failure happened during" everywhere
            // else in this class (a thrown action/watcher reports the dispatched event, not
            // anything it may have been about to do), and Strict's own report (via EventBus.
            // runActions' ordinary catch, since Strict throws) already uses triggeringEvent too -
            // this keeps Warning consistent with that instead of reporting the wrong event.
            OutputMismatchBehavior.Warning -> real.reportErrorAsync(triggeringEvent, violation)
            OutputMismatchBehavior.Silent -> Unit
        }
    }
}
