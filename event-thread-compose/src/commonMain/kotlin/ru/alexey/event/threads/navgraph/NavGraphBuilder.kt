package ru.alexey.event.threads.navgraph

import kotlin.reflect.KClass


class NavGraphBuilder<NAV : NavigationDestination> {
    private val screens: MutableMap<KClass<out NAV>, () -> Screen> = mutableMapOf()

    fun addScreen(event: KClass<out NAV>, screen: Screen) {
        screens[event] = { screen }
    }

    inline infix fun<reified T: NAV> KClass<T>.bind(builder: ScreenBuilder.() -> Unit) {
        val screen = ScreenBuilder().apply(builder)()
        addScreen(T::class, screen)
    }

    operator fun invoke(): NavGraph<NAV> {
        return NavGraph(screens)
    }
}

