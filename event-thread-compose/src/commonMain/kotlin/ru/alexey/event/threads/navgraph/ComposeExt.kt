package ru.alexey.event.threads.navgraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.scopeholder.ScopeHolder

@Composable
fun NavGraph(graphName: String, holder: ScopeHolder? = null) {
    val navigation = (holder ?: LocalScopeHolder.current).findOrLoad(graphName)
    val screen by navigation.resolveOrThrow<List<ReadyScreen>>().collectAsState()

    screen.lastOrNull()?.let { (current, params) ->
        current renderWith { params }
    }
}