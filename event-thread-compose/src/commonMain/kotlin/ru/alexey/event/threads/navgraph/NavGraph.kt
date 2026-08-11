package ru.alexey.event.threads.navgraph

import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass


typealias ReadyScreen = Pair<Screen, Parameters>


class NavGraph<NAV : NavigationDestination>(
    private val screens: Map<KClass<out NAV>, () -> Screen>
) {
    fun screenFor(clazz: KClass<out NAV>): Screen? = screens[clazz]?.invoke()
}
