package ru.alexey.event.threads.navgraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import ru.alexey.event.threads.ListBuilder
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.resources.resolve
import ru.alexey.event.threads.scope
import ru.alexey.event.threads.widget.Widget
import ru.alexey.event.threads.widget.createWidget
import ru.alexey.event.threads.widget.widget
import kotlin.properties.ReadOnlyProperty
import kotlin.random.Random
import kotlin.reflect.KClass

class ScreenBuilder {
    private val widgets: MutableMap<String, Widget> = mutableMapOf()
    private var content: @Composable Parameters.(Map<String, Widget>) -> Unit = {}
    private var key: String = Random.nextBytes(32).decodeToString()
    private val required: MutableList<KClass<out Any>> = mutableListOf()

    fun registerWidget(name: String, block: () -> Widget) {
        widgets[name] = block()
    }

    fun registerWidget(name: String, widget: Widget) {
        widgets[name] = widget
    }

    inline fun registerWidget(
        name: String,
        crossinline content: @Composable (modifier: Modifier) -> Unit
    ) {
        registerWidget(name, block = {
            object : Widget {
                override val name: String = name

                @Composable
                override fun Content(modifier: Modifier) {
                    scope(name) {
                        content(modifier)
                    }
                }
            }
        })
    }

    inline fun <reified T : Any> registerWidget(
        name: String,
        crossinline content: @Composable (T, Modifier) -> Unit
    ) {
        registerWidget(name, block = {
            object : Widget {
                override val name: String = name

                @Composable
                override fun Content(modifier: Modifier) {
                    scope(name) {
                        widget(T::class) {
                            content(it, modifier)
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

    fun require(required: KClass<out Any>) {
        this.required.add(required)
    }

    fun require(required: List<KClass<out Any>>) {
        this.required.addAll(required)
    }

    fun require(block: ListBuilder<KClass<out Any>>.() -> Unit) {
        this.required.addAll(
            ListBuilder<KClass<out Any>>().apply(block).invoke()
        )
    }

    operator fun invoke() = Screen(required, widgets, content, key)
}


class Screen(
    private val required: List<KClass<out Any>>,
    private val widgets: Map<String, Widget>,
    private val content: @Composable Parameters.(Map<String, Widget>) -> Unit,
    val key: String
) : Comparable<Screen> {
    override fun compareTo(other: Screen): Int {
        return key.compareTo(other.key)
    }

    fun checkParams(params: Parameters) {
        require(params.size == this.required.size) { "Incorrect number of params" }
        params.keys.forEach {
            require(it in this.required) { "Incorrect param type! Expect: ${it.simpleName}" }
        }
    }

    @Composable
    operator fun invoke(parameters: () -> Parameters) {
        with(parameters()) {
            content(widgets)
        }
    }

    @Composable
    infix fun renderWith(parameters: () -> Parameters) {
        with(parameters()) {
            content(widgets)
        }
    }
}

inline fun <reified T : Any> widget(
    name: String? = null,
    crossinline content: @Composable (T, Modifier) -> Unit
): ReadOnlyProperty<Any?, Widget> {
    return ReadOnlyProperty { thiRef: Any?, property ->
        object : Widget {
            override val name: String = name ?: property.name
            @Composable
            override fun Content(modifier: Modifier) {
                scope(name ?: property.name) {
                    val dc: StateFlow<T> by LocalScope.current
                    val state by dc.collectAsState()
                    content(state, modifier)
                }
            }
        }
    }
}