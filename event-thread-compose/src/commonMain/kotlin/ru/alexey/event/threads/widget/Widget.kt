package ru.alexey.event.threads.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.scope
import kotlin.reflect.KClass

interface Widget {
    val name: String

    @Composable
    fun Content(modifier: Modifier)

    @Composable
    operator fun invoke(modifier: Modifier) = Content(modifier)
}

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


@Composable
fun <T : Any> widget(
    clazz: KClass<T>,
    content: @Composable (T) -> Unit
) {
    val state by LocalScope.current[clazz]?.collectAsState()
        ?: error("Container with name: ${clazz.simpleName} was missing.")

    content(state)
}