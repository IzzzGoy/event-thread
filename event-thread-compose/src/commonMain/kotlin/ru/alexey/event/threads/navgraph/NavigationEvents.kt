package ru.alexey.event.threads.navgraph

import ru.alexey.event.threads.ExtendableEvent
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass


open class PopToScreen(val screen: Screen?): StrictEvent
object PopUp : PopToScreen(null)

interface NavigationDestination : ExtendableEvent {
    val name: String
    val params: Parameters
        get() = mapOf()
}