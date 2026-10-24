package ru.alexey.event.threads.navgraph

import kotlin.reflect.KClass


/** Receiver for [ru.alexey.event.threads.navgraph.navGraph]'s `builder` block - registers which
 * [Screen] renders for each [NavigationDestination] subtype before [invoke] produces the
 * [NavGraph]. */
class NavGraphBuilder<NAV : NavigationDestination> {
    private val screens: MutableMap<KClass<out NAV>, () -> Screen> = mutableMapOf()

    /** Registers [screen] to render for [event]. */
    fun addScreen(event: KClass<out NAV>, screen: Screen) {
        screens[event] = { screen }
    }

    /** `MyDestination::class bind { content { MyScreen() } }` - the usual DSL form of
     * [addScreen], building the [Screen] from [ScreenBuilder]. */
    inline infix fun<reified T: NAV> KClass<T>.bind(builder: ScreenBuilder.() -> Unit) {
        val screen = ScreenBuilder().apply(builder)()
        addScreen(T::class, screen)
    }

    /** Builds the [NavGraph]. */
    operator fun invoke(): NavGraph<NAV> {
        return NavGraph(screens)
    }
}

