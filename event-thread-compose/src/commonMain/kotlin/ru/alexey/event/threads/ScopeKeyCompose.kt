package ru.alexey.event.threads

import androidx.compose.runtime.Composable
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.typed.ScopeKey
import ru.alexey.event.threads.scopeholder.typed.typedParameters

/** [scope], keyed by a typed [ScopeKey] instead of a raw `String` name. */
@Composable
fun scope(target: ScopeKey<Unit>, scopeHolder: ScopeHolder? = null, content: @Composable () -> Unit) {
    scope(target.identity, scopeHolder = scopeHolder, content = content)
}

/** [scope] for a scope declaring real params - same typed-vs-untyped-`Parameters` win as
 * `ScopeHolder.load(target, params)` in `event-thread-core`. */
@Composable
fun <P : Any> scope(
    target: ScopeKey<P>,
    params: P,
    scopeHolder: ScopeHolder? = null,
    content: @Composable () -> Unit
) {
    scope(target.identity, parameters = typedParameters(params), scopeHolder = scopeHolder, content = content)
}
