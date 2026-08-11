package org.company.sample

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.cache.cacheJsonResource
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.navgraph.NavGraph
import ru.alexey.event.threads.navgraph.PopUp
import ru.alexey.event.threads.navgraph.navGraph
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.invoke
import ru.alexey.event.threads.resources.resolve
import ru.alexey.event.threads.resources.resolveOrDefault
import ru.alexey.event.threads.scope
import ru.alexey.event.threads.scopeholder.scopeHolder
import ru.alexey.event.threads.widget.createWidget
import kotlin.random.Random

// Widgets are reusable displays bound to a *fixed* named scope's existing state - drop one
// anywhere without re-wiring LocalScope/resolveOrThrow at each call site. Since "Work" and
// "Personal" are separate scopes (see below), each needs its own widget instance.
val workRemainingWidget = createWidget<List<Todo>>("Work") { todos, modifier ->
    Text(text = "${todos.count { !it.done }} left", modifier = modifier, style = MaterialTheme.typography.labelLarge)
}
val personalRemainingWidget = createWidget<List<Todo>>("Personal") { todos, modifier ->
    Text(text = "${todos.count { !it.done }} left", modifier = modifier, style = MaterialTheme.typography.labelLarge)
}

fun provideTodoScopeHolder() = scopeHolder {
    // "TodoListBase" is never loaded on its own - it's a template. Its threads/containers get
    // copied (by value/by closure) into every scope that `implements` it, each getting its own
    // independent copy since `implements` re-runs this factory fresh per implementer.
    scopeEmbedded("TodoListBase") { scopeParams ->
        config {
            createEventBus {
                watcher { Logger.d(tag = "TODO") { it.toString() } }
            }
        }

        val todos by datacontainer(cacheJsonResource(scopeParams.resolveOrDefault("todos_default"), emptyList<Todo>(), Json)) {}
        val showCompleted by datacontainer(flowResource(true)) {}

        // Composite state: `visibleTodos` is derived by combining two independent containers
        // of this same scope (the raw list + the filter flag) via `.transform()`, rather than
        // being written to directly by any thread.
        val visibleTodos by datacontainer(flowResource(VisibleTodos(emptyList()))) {
            transform(todos) { list, _ -> VisibleTodos(list) }
            transform(showCompleted) { show, current ->
                if (show) current else VisibleTodos(current.items.filterNot { it.done })
            }
        }

        // Force these three containers to build now, while `this` is still the template's own
        // ScopeBuilder - `implements` snapshot-copies containerBuilder entries into the child at
        // construction time, so anything realized lazily *after* that copy would never surface
        // on the child's own scope (and thus never be resolvable there).
        todos.value
        showCompleted.value
        visibleTodos.value

        threads {
            thread<AddTodo>().then(todos) { list, event ->
                list + Todo(id = Random.nextLong(), text = event.text)
            }
            thread<ToggleTodo>().then(todos) { list, event ->
                list.map { if (it.id == event.id) it.copy(done = !it.done) else it }
            }
            thread<DeleteTodo>().then(todos) { list, event ->
                list.filterNot { it.id == event.id }
            }
            thread<SaveTodoText>().then(todos) { list, event ->
                list.map { if (it.id == event.id) it.copy(text = event.text) else it }
            }
            thread<SetShowCompleted>().then(showCompleted) { _, event -> event.show }
        }
    }

    scopeEmbedded("Work") {}
    "Work" implements "TodoListBase"

    scopeEmbedded("Personal") {}
    "Personal" implements "TodoListBase"

    // Kept alive for the whole app session so the currently-selected tab survives navigation.
    // Deliberately event-driven (SelectList -> selectedList container) rather than a Compose
    // `remember` - the tab is app state, not transient UI state, so it belongs in a scope.
    scopeEmbedded("TodoTabs") {
        val selectedList by datacontainer(flowResource("Work")) {}

        threads {
            thread<SelectList>().then(selectedList) { _, event -> event.name }
        }
    }

    scopeEmbedded("TodoDraft") { scopeParams ->
        config {
            createEventBus {
                coroutineScope {
                    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                }
            }
        }
        val draft by datacontainer(flowResource(scopeParams.resolveOrDefault(""))) {
            coroutineScope {
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            }
        }

        threads {
            thread<SetDraftText>().then(draft) { _, event -> event.text }
        }
    }

    navGraph<TodoDestination>("Navigation", TodoListDestination) {
        TodoListDestination::class bind {
            content { TodoListScreen() }
        }
        EditTodoDestination::class bind {
            require { EditTodoParams::class() }
            content { EditTodoScreen(resolve<EditTodoParams>()) }
        }
    }
}

@Composable
fun TodoTabsScreen() {
    val holder = LocalScopeHolder.current
    val selectedList by LocalScope.current.resolveOrThrow<String>().collectAsState()

    Column(modifier = Modifier.imePadding().statusBarsPadding()) {
        TabRow(selectedTabIndex = if (selectedList == "Work") 0 else 1) {
            Tab(
                selected = selectedList == "Work",
                onClick = { holder + SelectList("Work") },
                text = { Text("Work") }
            )
            Tab(
                selected = selectedList == "Personal",
                onClick = { holder + SelectList("Personal") },
                text = { Text("Personal") }
            )
        }
        // Keying on selectedList forces this subtree to be torn down and rebuilt on switch,
        // so `scope()`'s remember{findOrLoad(name)} re-resolves against the new list's name
        // instead of holding onto whichever scope happened to load first.
        key(selectedList) {
            scope(selectedList, parameters = mapOf(String::class to { "todos_${selectedList.lowercase()}" })) {
                NavGraph("Navigation")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoListScreen() {
    val holder = LocalScopeHolder.current
    val listScope = LocalScope.current
    val visibleTodos by listScope.resolveOrThrow<VisibleTodos>().collectAsState()
    val showCompleted by listScope.resolveOrThrow<Boolean>().collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (listScope.key == "Work") "Work" else "Personal") },
                actions = {
                    if (listScope.key == "Work") {
                        workRemainingWidget(Modifier.padding(end = 12.dp))
                    } else {
                        personalRemainingWidget(Modifier.padding(end = 12.dp))
                    }
                    Text("Show done", style = MaterialTheme.typography.labelMedium)
                    Switch(
                        checked = showCompleted,
                        onCheckedChange = { holder + SetShowCompleted(it) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        },
        bottomBar = {
            scope("TodoDraft") {
                val draft by LocalScope.current.resolveOrThrow<String>().collectAsState()
                Surface(tonalElevation = 3.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .imePadding()
                            .padding(12.dp)
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { holder + SetDraftText(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("New todo") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = {
                            if (draft.isNotBlank()) {
                                holder + AddTodo(draft)
                                holder + SetDraftText("")
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (visibleTodos.items.isEmpty()) {
            Box(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No todos yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                items(visibleTodos.items, key = { it.id }) { todo ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = todo.text,
                                textDecoration = if (todo.done) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingContent = {
                            Checkbox(
                                checked = todo.done,
                                onCheckedChange = { holder + ToggleTodo(todo.id) }
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { holder + DeleteTodo(todo.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        },
                        modifier = Modifier.clickable { holder + EditTodoDestination(todo.id, todo.text) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTodoScreen(params: EditTodoParams) {
    val holder = LocalScopeHolder.current

    scope("TodoDraft", parameters = mapOf(String::class to { params.text })) {
        val draft by LocalScope.current.resolveOrThrow<String>().collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Edit todo") },
                    navigationIcon = {
                        IconButton(onClick = { holder + PopUp }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            holder + SaveTodoText(params.id, draft)
                            holder + PopUp
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize().padding(16.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { holder + SetDraftText(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}
