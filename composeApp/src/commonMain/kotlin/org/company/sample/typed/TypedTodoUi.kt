package org.company.sample.typed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.company.sample.AddTodo
import org.company.sample.models.LoadSuggestions
import org.company.sample.scopes.SuggestionsScopeContent
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.scopeholder.typed.RemoteData
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.company.sample.Personal
import org.company.sample.SelectList
import org.company.sample.TodoListBase
import org.company.sample.Work
import org.company.sample.scopes.TodoTabsScopeContent
import ru.alexey.event.threads.navgraph.NavGraph
import ru.alexey.event.threads.scope

/**
 * The tab row on the generated Compose entry point: inside `TodoTabsScopeContent { }` the
 * content runs on the typed TodoTabsScope - `selectedList` is its `SelectedListContainer` (a
 * StateFlow), `send` accepts only events TodoTabs handles. Same UI as TodoTabsScreen, without
 * `resolveOrThrow<String>()` or the holder.
 */
@Composable
fun TypedTodoTabsScreen() = TodoTabsScopeContent {
    val selected by selectedList.collectAsState()

    Column(modifier = Modifier.imePadding().statusBarsPadding()) {
        SuggestionsRow()
        TabRow(selectedTabIndex = if (selected == "Work") 0 else 1) {
            Tab(selected = selected == "Work", onClick = { send(SelectList("Work")) }, text = { Text("Work") })
            Tab(selected = selected == "Personal", onClick = { send(SelectList("Personal")) }, text = { Text("Personal") })
        }
        // The list screens are shared with the DSL app (TodoApp.kt), so they stay on `scope()`.
        val target = if (selected == "Work") Work else Personal
        scope(target, TodoListBase.Params(cacheKey = "todos_${selected.lowercase()}")) {
            NavGraph("Navigation")
        }
    }
}

/**
 * Ideas from the remote "Ideas" service - all generated from the schema: the request handler, its
 * Ktor API and the `RemoteData` container. Tapping an idea adds it to the selected list.
 */
@Composable
private fun SuggestionsRow() = SuggestionsScopeContent {
    val state by suggestions.collectAsState()
    val holder = LocalScopeHolder.current

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { send(LoadSuggestions) }, enabled = !state.isLoading) { Text("Suggest") }
        when (val s = state) {
            RemoteData.Idle -> {}
            is RemoteData.Loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            is RemoteData.Success -> s.value.forEach { idea ->
                TextButton(onClick = { holder + AddTodo(idea.text) }) { Text("+ ${idea.text}") }
            }
            is RemoteData.Failure -> Text("Couldn't load ideas (${s.error.message}) - try again", style = MaterialTheme.typography.bodySmall)
        }
    }
}
