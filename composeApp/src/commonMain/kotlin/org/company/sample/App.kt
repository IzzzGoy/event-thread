package org.company.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.company.sample.theme.AppTheme
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.ScopeHolder
import ru.alexey.event.threads.navgraph.ReadyScreen
import ru.alexey.event.threads.scope
import ru.alexey.event.threads.widget.createWidget


val startWidget = createWidget<Int>("StartScreen") {
    val scope = LocalScope.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Cyan)
            .clickable {
                scope + SetInt(it + 1)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(it.toString())
    }
}

@Composable
internal fun App() = AppTheme {


    ScopeHolder(::provideScopeHolder) {
        val holder = LocalScopeHolder.current

        val navigation = holder.findOrLoad("Navigation")
        val screen by navigation.resolveOrThrow<List<ReadyScreen>>().collectAsState()

        screen.lastOrNull()?.let { (current, params) ->
            current renderWith { params }
        }
    }
}

internal expect fun openUrl(url: String?)