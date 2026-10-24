package ru.alexey.event.threads

import androidx.compose.runtime.Composable
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.typed.ScopeKey
import ru.alexey.event.threads.scopeholder.typed.ScopeTyped

/**
 * [scope] for a typed scope, handing [content] the scope itself as [S] - its typed containers and
 * `send` functions instead of `resolveOrThrow<T>()` lookups. Generated scopes wrap this as
 * `<Name>ScopeContent(params) { ... }`.
 *
 * Fails if [target] is registered in the holder as something other than [S] - e.g. through the
 * DSL instead of `register<Name>Scope()`.
 */
@Composable
fun <P : Any, S : ScopeTyped<P>> typedScope(
    target: ScopeKey<P>,
    params: P,
    scopeHolder: ScopeHolder? = null,
    cast: (Scope) -> S?,
    content: @Composable S.() -> Unit,
) {
    val mount: @Composable () -> Unit = {
        val current = LocalScope.current
        val typed = checkNotNull(cast(current)) {
            "Scope '${target.identity}' is a ${current::class.simpleName}, not the typed scope - register it with its generated register...Scope()"
        }
        typed.content()
    }
    @Suppress("UNCHECKED_CAST")
    if (params == Unit) scope(target as ScopeKey<Unit>, scopeHolder, mount) else scope(target, params, scopeHolder, mount)
}

/** The enclosing scope as [S], or `null` if the nearest one isn't an [S] - what generated
 * `current<Name>Scope()` builds on. */
@Composable
fun <S : ScopeTyped<*>> currentTypedScope(cast: (Scope) -> S?): S? = cast(LocalScope.current)
