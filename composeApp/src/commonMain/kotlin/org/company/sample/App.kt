package org.company.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.company.sample.theme.AppTheme
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.LocalStateSaver
import ru.alexey.event.threads.ScopeHolder
import ru.alexey.event.threads.scope
import ru.alexey.event.threads.widget.createWidget
import ru.alexey.event.threads.widget.widget


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
    ScopeHolder(::provideScopeHolderTest) {
        var screen by remember {
            mutableIntStateOf(1)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (screen == 1) {
                scope("First") {
                    widget(Int::class, true) {

                        val holder = LocalScopeHolder.current

                        LaunchedEffect(Unit) {
                            delay(3_000)
                            holder + Update(it + 1)
                            delay(3_000)
                            screen = 2
                        }
                        Text(it.toString())
                    }
                    widget(String::class) {
                        Text(it, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            } else {
                scope("Test") {
                    LaunchedEffect(Unit) {
                        delay(3_000)
                        screen = 1
                    }
                    val localSaver = LocalStateSaver.current
                    TextButton(
                        onClick = {
                            localSaver.remove(Int::class)
                        }
                    ) {
                        widget(Int::class) {
                            Text("Test - $it")
                        }
                    }
                }
            }
        }
    }
}

internal expect fun openUrl(url: String?)