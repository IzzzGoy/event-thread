package ru.alexey.event.threads.navgraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.widget.Widget
import kotlin.reflect.KClass

class ScreenBuilder {
    private val widgets: MutableMap<String, Widget> = mutableMapOf()

    fun registerWidget(name: String, block: () -> Widget) {
        widgets[name] = block()
    }

    fun registerWidget(name: String, widget: Widget) {
        widgets[name] = widget
    }

    inline fun <reified T : Any> registerWidget(noinline block: () -> Widget) {
        val widget = block()
        registerWidget(widget.name, widget)
    }

}


class Screen(
    private val widgets: Map<String, Widget>,
    private val content: @Composable (Map<String, Widget>) -> Unit
)