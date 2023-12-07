package ru.alexey.event.threads.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.scope
import kotlin.reflect.KClass

interface Widget {
    val name: String

    @Composable
    fun Content()
}

inline fun <reified T : Any> createWidget(name: String, crossinline block: @Composable (T) -> Unit) = object : Widget {
    override val name: String = name
    @Composable
    override fun Content() {
        scope(name) {
            widget(T::class) {
                block(it)
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