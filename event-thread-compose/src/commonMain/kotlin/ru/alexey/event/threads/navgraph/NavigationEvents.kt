package ru.alexey.event.threads.navgraph

import ru.alexey.event.threads.ExtendableEvent
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass


internal class PopToScreen(private val screen: Screen): StrictEvent
internal object Pop: StrictEvent

interface NavigationDestination : ExtendableEvent {
    val name: String
    val params: List<KClass<out Any>>
}