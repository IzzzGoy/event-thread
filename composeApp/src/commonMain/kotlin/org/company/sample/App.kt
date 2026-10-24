package org.company.sample

import androidx.compose.runtime.Composable
import org.company.sample.theme.AppTheme
import ru.alexey.event.threads.ScopeHolder
import org.company.sample.typed.TypedTodoTabsScreen
import org.company.sample.typed.provideTypedTodoScopeHolder
import ru.alexey.event.threads.scope

/** `true`: run the app on the typed, generated scopes (typed/TodoScopes.kt, from
 * resources/event-thread/scopes.json) instead of the DSL ones in TodoApp.kt - same UI either way. */
private const val USE_TYPED_SCOPES = true

@Composable
internal fun App() = AppTheme {
    ScopeHolder(if (USE_TYPED_SCOPES) ::provideTypedTodoScopeHolder else ::provideTodoScopeHolder) {
        // Kept alive for the whole app session, independent of which nav screen is
        // currently composed - screens only get the *top* of the nav stack rendered,
        // so a scope scoped to one screen's composition would be disposed on navigation.
        // TodoTabs dependsOn TodoDomain (see TodoApp.kt) - loading one loads the other,
        // no need to nest scope() calls here just to keep TodoDomain's EventBus alive.
        if (USE_TYPED_SCOPES) {
            TypedTodoTabsScreen()          // the generated TodoTabsScopeContent { } mounts TodoTabs
        } else {
            scope(TodoTabs) {
                TodoTabsScreen()
            }
        }
    }
}

internal expect fun openUrl(url: String?)
