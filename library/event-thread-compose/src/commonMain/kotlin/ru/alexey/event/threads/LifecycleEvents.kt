package ru.alexey.event.threads

import ru.alexey.event.threads.bus.ExtendableEvent

/**
 * Dispatched into a `scope()`-mounted [ru.alexey.event.threads.Scope]'s bus to mirror its
 * composable's Android/lifecycle state and mount/unmount timing - see [scope]'s `LaunchedEffect`/
 * `DisposableEffect`. [VISIBLE]/[DISPOSED] track composition mount/unmount; the rest mirror
 * `androidx.lifecycle.Lifecycle.State`. Register a handler with `thread<LifecycleEvents>()` (an
 * [ExtendableEvent], so a handler for the interface itself receives every variant).
 */
interface LifecycleEvents: ExtendableEvent {
    interface CreatedMarker : LifecycleEvents
    data object CREATED: CreatedMarker

    interface StartedMarker : LifecycleEvents
    data object STARTED: StartedMarker
    interface ResumedMarker : LifecycleEvents
    data object RESUMED: ResumedMarker

    interface InitializedMarker : LifecycleEvents
    data object INITIALIZED: InitializedMarker

    interface DestroyedMarker : LifecycleEvents
    data object DESTROYED: DestroyedMarker

    interface DisposedMarker : LifecycleEvents
    data object DISPOSED: DisposedMarker

    interface VisibleMarker : LifecycleEvents
    data object VISIBLE: VisibleMarker
}
