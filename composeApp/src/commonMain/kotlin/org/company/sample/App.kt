package org.company.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import org.company.sample.theme.AppTheme
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.ScopeHolder
import ru.alexey.event.threads.navgraph.NavGraph
import ru.alexey.event.threads.navgraph.ReadyScreen
import ru.alexey.event.threads.scope
import ru.alexey.event.threads.widget.createWidget


val startWidget = createWidget<Int>("StartScreen") { it, modifier ->
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
        NavGraph("Navigation")
    }
}

internal expect fun openUrl(url: String?)