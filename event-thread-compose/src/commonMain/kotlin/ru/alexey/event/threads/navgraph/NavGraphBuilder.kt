package ru.alexey.event.threads.navgraph

import ru.alexey.event.threads.Scope

/*
class NavGraphBuilder {
    private val screens: MutableMap<String, Screen> = mutableMapOf()

    fun createScreen(builder: ScreenBuilder.() -> Unit) {
        val screen = ScreenBuilder().apply(builder)()
        screens[screen.key] = screen
    }

    operator fun invoke(scope: Scope) = NavGraph(scope, screens)
}*/
