package ru.alexey.event.threads.navgraph

import ru.alexey.event.threads.bus.ExtendableEvent
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.resources.Parameters


open class PopToScreen(val screen: Screen?): StrictEvent
object PopUp : PopToScreen(null)

interface NavigationDestination : ExtendableEvent {
    val params: Parameters
        get() = mapOf()
}