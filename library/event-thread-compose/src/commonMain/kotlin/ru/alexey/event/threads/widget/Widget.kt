package ru.alexey.event.threads.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.LocalStateSaver
import ru.alexey.event.threads.scope
import kotlin.reflect.KClass

/** A reusable display bound to a fixed named scope's existing state - drop one anywhere without
 * re-wiring [ru.alexey.event.threads.LocalScope]/`resolveOrThrow` at each call site. Build one
 * with [createWidget]. */
interface Widget {
    val name: String

    @Composable
    fun Content(modifier: Modifier)

    @Composable
    operator fun invoke(modifier: Modifier) = Content(modifier)
}

/** Builds a [Widget] bound to the scope named [name], rendering [block] with that scope's
 * `T`-typed container's current value. */
inline fun <reified T : Any> createWidget(name: String, crossinline block: @Composable (T, Modifier) -> Unit) = object : Widget {
    override val name: String = name
    @Composable
    override fun Content(modifier: Modifier) {
        scope(name) {
            widget(T::class) {
                block(it, modifier)
            }
        }
    }
}


/**
 * Collects the nearest [ru.alexey.event.threads.LocalScope]'s `clazz`-typed container and renders
 * [content] with its current value - throws if that scope has no such container registered. When
 * [isSavable] is `true`, the last value is written to [ru.alexey.event.threads.LocalStateSaver]
 * when this composable leaves composition (see [ru.alexey.event.threads.StateSaver]).
 */
@Composable
fun <T : Any> widget(
    clazz: KClass<T>,
    isSavable: Boolean = false,
    content: @Composable (T) -> Unit
) {

    val state by LocalScope.current[clazz]?.collectAsState()
        ?: error("Container with name: ${clazz.simpleName} was missing.")

    if (isSavable) {
        val saver = LocalStateSaver.current
        DisposableEffect(Unit) {
            onDispose {
                saver.save(clazz, state)
            }
        }
    }

    content(state)
}