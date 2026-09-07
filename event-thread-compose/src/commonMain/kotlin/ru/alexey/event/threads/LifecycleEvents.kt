package ru.alexey.event.threads

import ru.alexey.event.threads.bus.ExtendableEvent

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
