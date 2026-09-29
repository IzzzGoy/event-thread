package ru.alexey.event.threads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.scopeholder.ScopeHolder
import kotlin.reflect.KClass

@Composable
fun scope(
    name: String,
    parameters: Parameters? = null,
    scopeHolder: ScopeHolder? = null,
    content: @Composable () -> Unit
) {
    val holder = scopeHolder ?: LocalScopeHolder.current
    val counter = LocalScopeCounter.current
    val saver = LocalStateSaver.current

    val scope = remember {
        holder.findOrLoad(name) {
            (parameters ?: emptyMap()) + saver.savedParams
        }
    }

    counter.register(name)



    DisposableEffect(Unit) {
        onDispose {
            if (counter.unregister(name)) {
                holder.free(scope.key)
            }
        }
    }

    CompositionLocalProvider(LocalScope provides scope) {
        content()
    }
}

/*@Composable
fun scope(name: String, scopeHolder: ScopeHolder? = null, content: @Composable () -> Unit) {
    val holder = scopeHolder ?: LocalScopeHolder.current
    val counter = LocalScopeCounter.current

    val scope = remember {
        holder.findOrLoad(name)
    }

    counter.register(name)



    DisposableEffect(Unit) {
        onDispose {
            if (counter.unregister(name)) {
                holder.free(scope.key)
            }
        }
    }

    CompositionLocalProvider(LocalScope provides  scope) {
        content()
    }
}*/

@Composable
fun ScopeHolder(block: () -> ScopeHolder, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalScopeHolder provides block(),
        LocalScopeCounter provides ScopeCounter(mutableMapOf()),
        LocalStateSaver provides DefaultStateSaver()
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