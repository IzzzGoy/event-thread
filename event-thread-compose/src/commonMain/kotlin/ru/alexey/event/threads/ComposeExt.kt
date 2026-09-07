package ru.alexey.event.threads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.alexey.event.threads.bus.Event
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
    val lifecycleOwner = LocalLifecycleOwner.current

    // Both keyed on `name`, not `Unit` - so a call site that switches which scope it mounts
    // (e.g. a tab switcher passing a different `name` without wrapping in `key()`) correctly
    // tears down the old scope and resolves the new one against its *current* `parameters`,
    // instead of silently keeping the scope (and params) captured on first mount.
    val scope = remember(name) {
        holder.findOrLoad(name) {
            (parameters ?: emptyMap()) + saver.savedParams
        }
    }

    LaunchedEffect(name) {
        lifecycleOwner.lifecycle.currentStateFlow.collect { state ->
            scope + when(state) {
                Lifecycle.State.DESTROYED -> LifecycleEvents.DESTROYED
                Lifecycle.State.INITIALIZED -> LifecycleEvents.INITIALIZED
                Lifecycle.State.CREATED -> LifecycleEvents.CREATED
                Lifecycle.State.STARTED -> LifecycleEvents.STARTED
                Lifecycle.State.RESUMED -> LifecycleEvents.RESUMED
            }
        }
    }

    DisposableEffect(name) {
        // register() lives here, not as a plain statement in the composable body - this makes
        // it run exactly once per (name) mount, paired 1:1 with onDispose's unregister() below.
        // A plain top-level call would re-run on every recomposition (e.g. every keystroke in a
        // sibling text field), incrementing the counter without a matching decrement and
        // leaving `holder.free()` never firing.
        counter.register(name)
        scope + LifecycleEvents.VISIBLE
        onDispose {
            scope + LifecycleEvents.DISPOSED
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
    /*LaunchedEffect(Unit) {
        eventBus.external(clazz) {
            if (clazz.isInstance(it)) {
                block(it as T)
            }
        }
    }*/
}