package org.company.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import org.company.sample.theme.AppTheme

@Composable
internal fun App() = AppTheme {

    val scope = remember {
        mainScreenScope()
    }
    val state by scope.resolveOrThrow<Long>().collectAsState()

    LaunchedEffect(state) {
        Logger.d("STATE") { state.toString() }
    }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Text(
            text = state.toString(),
            modifier = Modifier.clickable {
                scope + AnotherEvent
            }
        )
        Button(
            onClick = {
                scope + TestEvent
            }
        ) {

        }
    }
}

internal expect fun openUrl(url: String?)