package ru.alexey.event.threads.navgraph

import androidx.compose.runtime.Composable
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.resources.resolve
import ru.alexey.event.threads.scope
import ru.alexey.event.threads.widget.Widget
import ru.alexey.event.threads.widget.createWidget
import ru.alexey.event.threads.widget.widget
import kotlin.random.Random

class ScreenBuilder {
    private val widgets: MutableMap<String, Widget> = mutableMapOf()
    private var content: @Composable Parameters.(Map<String, Widget>) -> Unit = {}
    private var key: String = Random.nextBytes(32).decodeToString()

    fun registerWidget(name: String, block: () -> Widget) {
        widgets[name] = block()
    }

    fun registerWidget(name: String, widget: Widget) {
        widgets[name] = widget
    }

    inline fun registerWidget(name: String, crossinline content: @Composable () -> Unit) {
        registerWidget(name, block = {
            object : Widget {
                override val name: String = name
                @Composable
                override fun Content() {
                    scope(name) {
                        content()
                    }
                }
            }
        })
    }

    inline fun<reified T : Any> registerWidget(name: String, crossinline content: @Composable (T) -> Unit) {
        registerWidget(name, block = {
            object : Widget {
                override val name: String = name
                @Composable
                override fun Content() {
                    scope(name) {
                        widget(T::class) {
                            content(it)
                        }
                    }
                }
            }
        })
    }

    inline fun <reified T : Any> registerWidget(noinline block: () -> Widget) {
        val widget = block()
        registerWidget(widget.name, widget)
    }


    fun content(content: @Composable Parameters.(Map<String, Widget>) -> Unit) {
        this.content = content
    }

    fun key(key: String) {
        this.key = key
    }

    fun key(block: () -> String) {
        this.key = block()
    }

    operator fun invoke() = Screen(widgets, content, key)
}




class Screen(
    private val widgets: Map<String, Widget>,
    private val content: @Composable Parameters.(Map<String, Widget>) -> Unit,
    val key: String
) : Comparable<Screen> {
    override fun compareTo(other: Screen): Int {
        return key.compareTo(other.key)
    }

    @Composable operator fun invoke(parameters: () -> Parameters) {
        with(parameters()) {
            content(widgets)
        }
    }

    @Composable infix fun renderWith(parameters: () -> Parameters) {
        with(parameters()) {
            content(widgets)
        }
    }
}