package ru.alexey.event.threads.navgraph

import ru.alexey.event.threads.bus.ExtendableEvent
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.resources.Parameters


/** Pops a [ru.alexey.event.threads.navgraph.navGraph]'s back stack down to (and including)
 * [screen] - or to `null`, meaning "just pop one" (see [PopUp]). A no-op if [screen] isn't on the
 * stack; never pops the last remaining screen. */
open class PopToScreen(val screen: Screen?): StrictEvent

/** Pops the current [ru.alexey.event.threads.navgraph.navGraph] screen - `holder + PopUp`.
 * Equivalent to `PopToScreen(null)`. */
object PopUp : PopToScreen(null)

/** Marker for events that push a screen onto a [ru.alexey.event.threads.navgraph.navGraph]'s back
 * stack - implement this for each destination and register a matching [Screen] via
 * `NAV::class bind { }` (see [ru.alexey.event.threads.navgraph.NavGraphBuilder]). [params] are
 * checked against the destination's registered [Screen.checkParams] before the screen renders. */
interface NavigationDestination : ExtendableEvent {
    val params: Parameters
        get() = mapOf()
}