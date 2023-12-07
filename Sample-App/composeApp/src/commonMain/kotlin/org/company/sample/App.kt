package org.company.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import org.company.sample.theme.AppTheme
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.ScopeHolder
import ru.alexey.event.threads.scope
import ru.alexey.event.threads.widget.createWidget
import ru.alexey.event.threads.widget.widget



val startWidget = createWidget<Int>("StartScreen") {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Cyan),
        contentAlignment = Alignment.Center
    ) {
        Text(it.toString())
    }
}

@Composable
internal fun App() = AppTheme {



    ScopeHolder(::provideScopeHolder) {
        val holder = LocalScopeHolder.current

        startWidget.Content()

        scope("Global") {
            LaunchedEffect(Unit) {
                repeat(5) {
                    delay(300)
                    holder + Global
                }
            }
        }
    }
}

internal expect fun openUrl(url: String?)