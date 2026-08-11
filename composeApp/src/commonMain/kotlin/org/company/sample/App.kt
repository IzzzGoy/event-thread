package org.company.sample

import androidx.compose.runtime.Composable
import org.company.sample.theme.AppTheme
import ru.alexey.event.threads.ScopeHolder
import ru.alexey.event.threads.scope

@Composable
internal fun App() = AppTheme {
    ScopeHolder(::provideTodoScopeHolder) {
        // Kept alive for the whole app session, independent of which nav screen is
        // currently composed - screens only get the *top* of the nav stack rendered,
        // so a scope scoped to one screen's composition would be disposed on navigation.
        scope("TodoTabs") {
            TodoTabsScreen()
        }
    }
}

internal expect fun openUrl(url: String?)
