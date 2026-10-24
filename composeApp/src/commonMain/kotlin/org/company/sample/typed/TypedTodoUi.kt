package org.company.sample.typed

import androidx.compose.foundation.layout.Column
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
