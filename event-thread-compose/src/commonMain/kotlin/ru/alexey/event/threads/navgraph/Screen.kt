package ru.alexey.event.threads.navgraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.utils.ListBuilder
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.scope
import ru.alexey.event.threads.widget.Widget
import ru.alexey.event.threads.widget.widget
import kotlin.properties.ReadOnlyProperty
import kotlin.random.Random
import kotlin.reflect.KClass

/** Receiver for `NAV::class bind { }` (see [ru.alexey.event.threads.navgraph.NavGraphBuilder]) -
 * declares a [Screen]'s [content], required navigation params ([require]) and named [Widget]s
 * before [invoke] builds it. */
class ScreenBuilder {
    private val widgets: MutableMap<String, Widget> = mutableMapOf()
    private var content: @Composable Parameters.(Map<String, Widget>) -> Unit = {}
    private var key: String = Random.nextBytes(32).decodeToString()
    private val required: MutableList<KClass<out Any>> = mutableListOf()

    /** Registers a [Widget] under [name], built lazily by [block]. */
    fun registerWidget(name: String, block: () -> Widget) {
        widgets[name] = block()
    }

    /** Registers an already-built [widget] under [name]. */
    fun registerWidget(name: String, widget: Widget) {
        widgets[name] = widget
    }

    /** Registers a plain composable [content] as a [Widget] under [name], mounted in its own
     * `scope(name) { }`. */
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

    /** Registers [content] as a [Widget] under [name], mounted in its own `scope(name) { }` and
     * fed the current value of that scope's `T`-typed container. */
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

    /** Registers a [Widget] built by [block], keyed by its own [Widget.name]. */
    inline fun <reified T : Any> registerWidget(noinline block: () -> Widget) {
        val widget = block()
        registerWidget(widget.name, widget)
    }


    /** Sets this screen's rendered [content], given its navigation [Parameters] as the receiver
     * and its registered [Widget]s by name. */
    fun content(content: @Composable Parameters.(Map<String, Widget>) -> Unit) {
        this.content = content
    }

    /** Sets this screen's [Screen.key] (used for [Screen.compareTo] and `PopToScreen`). Defaults
     * to a random value if never called. */
    fun key(key: String) {
        this.key = key
    }

    /** [key], computed lazily. */
    fun key(block: () -> String) {
        this.key = block()
    }

    /** Declares that navigating to this screen must supply a [required] parameter type - checked
     * by [Screen.checkParams]. */
    fun require(required: KClass<out Any>) {
        this.required.add(required)
    }

    /** [require] for multiple parameter types. */
    fun require(required: List<KClass<out Any>>) {
        this.required.addAll(required)
    }

    /** [require] via the `{ Foo::class(); Bar::class() }` [ListBuilder] DSL. */
    fun require(block: ListBuilder<KClass<out Any>>.() -> Unit) {
        this.required.addAll(
            ListBuilder<KClass<out Any>>().apply(block).invoke()
        )
    }

    /** Builds the [Screen]. */
    operator fun invoke() = Screen(required, widgets, content, key)
}


/** One screen of a [ru.alexey.event.threads.navgraph.navGraph] - its [content], required
 * navigation [Parameters] types, and named [Widget]s. Build one via [ScreenBuilder]/`bind { }`,
 * not directly. */
class Screen(
    private val required: List<KClass<out Any>>,
    private val widgets: Map<String, Widget>,
    private val content: @Composable Parameters.(Map<String, Widget>) -> Unit,
    val key: String
) : Comparable<Screen> {
    override fun compareTo(other: Screen): Int {
        return key.compareTo(other.key)
    }

    /** Validates that [params] exactly matches this screen's [ScreenBuilder.require]d parameter
     * types - throws otherwise. Checked when a [NavigationDestination] event pushes this screen. */
    fun checkParams(params: Parameters) {
        require(params.size == this.required.size) { "Incorrect number of params" }
        params.keys.forEach {
            require(it in this.required) { "Incorrect param type! Expect: ${it.simpleName}" }
        }
    }

    /** Renders this screen's content with [parameters] as the receiver. */
    @Composable
    operator fun invoke(parameters: () -> Parameters) {
        with(parameters()) {
            content(widgets)
        }
    }

    /** [invoke], as an infix call - `screen renderWith { params }` (used by [ru.alexey.event.
     * threads.navgraph.NavGraph]). */
    @Composable
    infix fun renderWith(parameters: () -> Parameters) {
        with(parameters()) {
            content(widgets)
        }
    }
}

/** Property-delegate form of `widget<T> { }`: `val price by widget<Price> { state, mod -> }` -
 * mounts its own `scope(name) { }` (defaulting to the property's own name) and renders [content]
 * with that scope's `T`-typed container, collected as state. */
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