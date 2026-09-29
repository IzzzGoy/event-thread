package ru.alexey.event.threads.navgraph

import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass


/** A [Screen] paired with the [Parameters] it was pushed with - what actually sits on a nav
 * graph's back stack (see the `navGraph` scope's `stack` container in [NavGraphBuilder]/
 * [ru.alexey.event.threads.navgraph.navGraph]). */
typealias ReadyScreen = Pair<Screen, Parameters>


/** The registered `NAV::class -> Screen` mapping for one navigation graph - built by
 * [NavGraphBuilder], resolved via [screenFor] when a [NavigationDestination] event is dispatched. */
class NavGraph<NAV : NavigationDestination>(
    private val screens: Map<KClass<out NAV>, () -> Screen>
) {
    /** The [Screen] registered for [clazz] (via [NavGraphBuilder.bind]), or `null` if none is. */
    fun screenFor(clazz: KClass<out NAV>): Screen? = screens[clazz]?.invoke()
}
