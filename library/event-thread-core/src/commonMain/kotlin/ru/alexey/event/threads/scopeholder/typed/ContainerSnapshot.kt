package ru.alexey.event.threads.scopeholder.typed

import ru.alexey.event.threads.datacontainer.Datacontainer

/**
 * Read-only view of container [C]'s state, for a handler that needs data from a container it
 * doesn't update. Two things a plain container reference wouldn't give it:
 * - it can't [Datacontainer.update] - state stays owned by the handlers declared to process it;
 * - [C] is part of the type, so `ContainerSnapshot<DraftDatacontainerTyped, String>` can't be
 *   satisfied by some other `String` container.
 *
 * [map] narrows it to the piece of data the handler actually uses ("specific data extraction"):
 * ```
 * class SelectListImpl(deps: Deps) : SelectListEventCascader(deps) {
 *     private val hasDraft = deps.draft.map { it.isNotBlank() }   // ContainerSnapshot<DraftDatacontainerTyped, Boolean>
 *     override fun cascade(input: SelectList) = if (hasDraft.value) listOf(SetDraftText("")) else emptyList()
 * }
 * ```
 * [value] reads the container's state at the moment it's called - a snapshot per read, not a
 * frozen copy - and a mapped view recomputes its extraction on each read (cheap and never stale).
 */
class ContainerSnapshot<C : Datacontainer<*>, out R> internal constructor(
    private val read: () -> R,
) {
    val value: R get() = read()

    fun <V> map(extract: (R) -> V): ContainerSnapshot<C, V> = ContainerSnapshot { extract(read()) }
}

/** Read-only [ContainerSnapshot] of this container. */
fun <T, C : Datacontainer<T>> C.snapshot(): ContainerSnapshot<C, T> = ContainerSnapshot { value }
