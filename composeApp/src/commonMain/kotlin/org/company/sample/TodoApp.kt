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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import ru.alexey.event.threads.LifecycleEvents
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.cache.cacheJsonResource
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.di.get
import ru.alexey.event.threads.di.koin.asDependencyProvider
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

private const val ADD_LIMIT = 5

fun provideTodoScopeHolder() = scopeHolder {
    // Dependency provider example: a standalone Koin instance (koinApplication, not the global
    // context - ScopeHolder is already this app's own single composition root, so there's no
    // need for a process-wide Koin singleton) registers `Json`, and event-thread-koin's
    // asDependencyProvider() adapts it to the library's DependencyProvider. Every scope below
    // resolves it via `dependencyProvider.get<Json>()` instead of the kotlinx.serialization
    // default directly - swapping DI frameworks (or dropping down to DummyProvider) only means
    // changing this one registration.
    val koin = koinApplication {
        // `single<Json> { Json }`, not `single { Json }` - the bare `Json` expression resolves to
        // the `Json.Default` companion object, so Koin's reified type inference would otherwise
        // register the definition under `Json.Default::class` instead of the sealed `Json::class`
        // that `dependencyProvider.get<Json>()` below actually asks for.
        modules(module { single<Json> { Json } })
    }.koin
    dependencyProvider(koin.asDependencyProvider())

    // AddLimitReached is domain-to-UI only - Navigation/TodoDraft/TodoTabs have no handler for
    // it anyway, but routing it explicitly (rather than broadcasting) states that intent and
    // exercises the same targeted-delivery path the domain scope relies on to reach whichever
    // list is currently active.
    AddLimitReached::class consume listOf("Work", "Personal")

    // "TodoTabs" is the one thing loaded from Compose (App.kt) for the whole app session -
    // dependsOn piggybacks TodoDomain's load/free onto it, so it's always around to see AddTodo
    // regardless of which tab is mounted, without Compose needing to know TodoDomain exists.
    "TodoTabs" dependsOn "TodoDomain"

    // A business-rule layer with no UI and no access to Work/Personal's containers - it only
    // knows about the same events the UI dispatches, keeps its own event-sourced tally, and
    // reports its verdict back as a new event.
    scopeEmbedded("TodoDomain") {
        val totalAdded by datacontainer(flowResource(0)) {
            coroutineScope {
                CoroutineScope(Dispatchers.Main + SupervisorJob())
            }
            watcher { state ->
                Logger.d(tag = "TodoDomainState") {
                    state.toString()
                }
            }
        }
        totalAdded.value

        threads {
            thread<AddTodo>().then(totalAdded) { state, _ ->
                state + 1
            }.end {
                if (totalAdded.value >= ADD_LIMIT) {
                    eventBus += AddLimitReached(totalAdded.value)
                }
            }
        }
    }

    // "TodoListBase" is never loaded on its own - it's a template. Its threads/containers get
    // copied (by value/by closure) into every scope that `implements` it, each getting its own
    // independent copy since `implements` re-runs this factory fresh per implementer.
    scopeEmbedded("TodoListBase") { scopeParams ->
        config {
            createEventBus {
                watcher { Logger.d(tag = "TODO") { it.toString() } }
            }
        }

        // Reads always come from the in-memory Flow side; the JSON file is only ever read once
        // (to hydrate on first load) and then only ever written to, never re-read on every
        // add/toggle/delete. `Json` itself comes from `dependencyProvider` (see
        // provideTodoScopeHolder) rather than the kotlinx.serialization default directly.
        val todos by datacontainer(
            cacheJsonResource(
                key = scopeParams.resolveOrDefault("todos_default"),
                initial = emptyList<Todo>(),
                json = dependencyProvider.get<Json>()
            )
        ) {}
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

        // Materializes "TodoDomain"'s verdict locally - this scope never reads TodoDomain's own
        // container (containers are scope-local, not shared across scopes), it only reacts to
        // the event TodoDomain sent.
        val limitHits by datacontainer(flowResource(0)) {}

        // Force these four containers to build now, while `this` is still the template's own
        // ScopeBuilder - `implements` snapshot-copies containerBuilder entries into the child at
        // construction time, so anything realized lazily *after* that copy would never surface
        // on the child's own scope (and thus never be resolvable there).
        todos.value
        showCompleted.value
        visibleTodos.value
        limitHits.value

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
            thread<AddLimitReached>().then(limitHits) { count, _ -> count + 1 }
            thread<LifecycleEvents>().end {
                Logger.d(tag = "LifecycleEvents") { it.toString() }
            }
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
        // `scope()` keys its own remember/dispose on `name`, so switching selectedList here
        // correctly tears down the old list's scope and resolves the new one against its own
        // parameters - no manual `key()` wrapping needed at the call site.
        scope(selectedList, parameters = mapOf(String::class to { "todos_${selectedList.lowercase()}" })) {
            NavGraph("Navigation")
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
    val limitHits by listScope.resolveOrThrow<Int>().collectAsState()

    // "TodoDomain" never touches this scope's UI directly - it only sent an event. limitHits
    // just counts occurrences so each new one is a distinct value LaunchedEffect can key on
    // (a plain "warn: Boolean" staying true wouldn't refire for a second, later warning).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(limitHits) {
        if (limitHits > 0) {
            snackbarHostState.showSnackbar("That's a lot of todos overall ($limitHits warnings) - domain says slow down")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
