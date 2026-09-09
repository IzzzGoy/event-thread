package ru.alexey.event.threads.navgraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.rememberOrLoadScope
import ru.alexey.event.threads.scopeholder.ScopeHolder

@Composable
fun NavGraph(graphName: String, holder: ScopeHolder? = null) {
    val scopeHolder = holder ?: LocalScopeHolder.current

    // Shares `scope()`'s find-or-load/dispose/counter bookkeeping via `rememberOrLoadScope`, but
    // - unlike `scope()` - does NOT provide this graph's own scope as `LocalScope` for the
    // screens it renders below: each registered screen resolves whatever `LocalScope` its caller
    // already established (e.g. a "Work"/"Personal" tab scope), not this nav graph's own scope.
    val navigation = rememberOrLoadScope(graphName, scopeHolder) { scopeHolder.findOrLoad(graphName) }

    val screen by navigation.resolveOrThrow<List<ReadyScreen>>().collectAsState()

    screen.lastOrNull()?.let { (current, params) ->
        current renderWith { params }
    }
}