package org.company.sample

import kotlinx.serialization.Serializable
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.navgraph.NavigationDestination
import ru.alexey.event.threads.resources.Parameters

@Serializable
data class Todo(
    val id: Long,
    val text: String,
    val done: Boolean = false
)

data class AddTodo(val text: String) : StrictEvent
data class ToggleTodo(val id: Long) : StrictEvent
data class DeleteTodo(val id: Long) : StrictEvent
data class SaveTodoText(val id: Long, val text: String) : StrictEvent
data class SetDraftText(val text: String) : StrictEvent
data class SetShowCompleted(val show: Boolean) : StrictEvent
data class SelectList(val name: String) : StrictEvent

// Wrapped in its own type (rather than reusing List<Todo>) so it can be registered as its
// own container alongside the raw `todos` list - both are List<Todo>-shaped underneath and
// would collide on the same erased KClass<List<*>> key if not distinguished this way.
data class VisibleTodos(val items: List<Todo>)

sealed interface TodoDestination : NavigationDestination

data object TodoListDestination : TodoDestination

data class EditTodoParams(val id: Long, val text: String)

data class EditTodoDestination(val id: Long, val text: String) : TodoDestination {
    override val params: Parameters get() = mapOf(EditTodoParams::class to { EditTodoParams(id, text) })
}
