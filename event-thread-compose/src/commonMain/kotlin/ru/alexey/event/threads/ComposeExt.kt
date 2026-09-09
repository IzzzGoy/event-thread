package ru.alexey.event.threads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.scopeholder.ScopeHolder

// Shared by `scope()` and `NavGraph()`: find-or-load the named scope exactly once per `name`
// mount, and free it when the last mounted user of that name is disposed. Deliberately doesn't
// provide `LocalScope` or touch lifecycle/params - `NavGraph` needs this bookkeeping but must
// NOT push its own scope onto `LocalScope`, since the screens it renders resolve *their caller's*
// ambient scope (e.g. a "Work"/"Personal" tab), not the nav graph's own.
@Composable
internal fun rememberOrLoadScope(
    name: String,
    scopeHolder: ScopeHolder,
    resolve: () -> Scope
): Scope {
    val counter = LocalScopeCounter.current

    // Keyed on `name`, not `Unit` - see `scope()`'s doc below for why.
    val scope = remember(name) { resolve() }

    DisposableEffect(name) {
        // register() lives here, not as a plain statement in the composable body - this makes
        // it run exactly once per (name) mount, paired 1:1 with onDispose's unregister() below.
        // A plain top-level call would re-run on every recomposition (e.g. every keystroke in a
        // sibling text field), incrementing the counter without a matching decrement and
        // leaving `holder.free()` never firing.
        counter.register(name)
        onDispose {
            if (counter.unregister(name)) {
                scopeHolder.free(scope.key)
            }
        }
    }

    return scope
}

@Composable
fun scope(
    name: String,
    parameters: Parameters? = null,
    scopeHolder: ScopeHolder? = null,
    content: @Composable () -> Unit
) {
    val holder = scopeHolder ?: LocalScopeHolder.current
    val saver = LocalStateSaver.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Keyed on `name`, not `Unit` - so a call site that switches which scope it mounts
    // (e.g. a tab switcher passing a different `name` without wrapping in `key()`) correctly
    // tears down the old scope and resolves the new one against its *current* `parameters`,
    // instead of silently keeping the scope (and params) captured on first mount.
    val scope = rememberOrLoadScope(name, holder) {
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
        scope + LifecycleEvents.VISIBLE
        onDispose {
            scope + LifecycleEvents.DISPOSED
        }
    }

    CompositionLocalProvider(LocalScope provides scope) {
        content()
    }
}

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