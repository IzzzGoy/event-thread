package ru.alexey.event.threads

import androidx.compose.runtime.*
import ru.alexey.event.threads.scopeholder.ScopeHolder
import kotlin.reflect.KClass

@Composable
fun scope(name: String, scopeHolder: ScopeHolder? = null, content: @Composable () -> Unit) {
    val holder = scopeHolder ?: LocalScopeHolder.current


    val scope = remember {
        holder.findOrLoad(name)
    }

    DisposableEffect(Unit) {
        onDispose {
            holder.free(scope.key)
        }
    }

    CompositionLocalProvider(LocalScope provides  scope) {
        content()
    }
}

@Composable
fun ScopeHolder(block: () -> ScopeHolder, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalScopeHolder provides  block()
    ) {
        content()
    }
}

@Composable
fun<T: Event> Scope.external(clazz: KClass<T>, block: (T) -> Unit) {
    LaunchedEffect(Unit) {
        eventBus.external(clazz) {
            if (clazz.isInstance(it)) {
                block(it as T)
            }
        }
    }
}