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
//
// [instance] identifies what's mounted: the scope [name] itself, or - for one of several typed
// instances of a key (see ScopeHolder) - its (key, params). Counting and freeing per instance is
// what keeps unmounting one todo list's screen from freeing another list's scope.
@Composable
internal fun rememberOrLoadScope(
    name: String,
    scopeHolder: ScopeHolder,
    instance: Any = name,
    resolve: () -> Scope
): Scope {
    val counter = LocalScopeCounter.current

    // Keyed on the instance, not `Unit` - see `scope()`'s doc below for why.
    val scope = remember(instance) { resolve() }

    // Counted by the resolved scope instance itself, not by how it was asked for: a widget's
    // `scope("Work")` and the tab's typed `scope(Work, params)` reach the same Work instance and
    // must share one count - counting them apart let the widget's unmount free the tab's scope.
    DisposableEffect(scope) {
        // register() lives here, not as a plain statement in the composable body - this makes
        // it run exactly once per (name) mount, paired 1:1 with onDispose's unregister() below.
        // A plain top-level call would re-run on every recomposition (e.g. every keystroke in a
        // sibling text field), incrementing the counter without a matching decrement and
        // leaving `holder.free()` never firing.
        counter.register(scope)
        onDispose {
            if (counter.unregister(scope)) {
                // This exact instance - free(scope.key) would free every instance of the key.
                scopeHolder.free(scope)
            }
        }
    }

    return scope
}

/**
 * Mounts the named scope for as long as this composable stays in composition: finds or loads it
 * (from [scopeHolder], or [LocalScopeHolder] if omitted), provides it as [LocalScope] to
 * [content], forwards Android/lifecycle state changes and mount/unmount timing into it as
 * [LifecycleEvents], and frees it via [ScopeHolder.free] once the last mounted user of [name]
 * unmounts (see [ScopeCounter]). Re-mounting under a different [name] (e.g. a tab switcher)
 * correctly tears down the old scope and resolves the new one against the [parameters] current
 * at that point, without needing `key()` at the call site.
 */
@Composable
fun scope(
    name: String,
    parameters: Parameters? = null,
    scopeHolder: ScopeHolder? = null,
    content: @Composable () -> Unit
) = mountScope(name, name, parameters, scopeHolder, content)

/** [scope], mounted as [instance] - see [rememberOrLoadScope]. */
@Composable
internal fun mountScope(
    name: String,
    instance: Any,
    parameters: Parameters?,
    scopeHolder: ScopeHolder?,
    content: @Composable () -> Unit
) {
    val holder = scopeHolder ?: LocalScopeHolder.current
    val saver = LocalStateSaver.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Keyed on `name`, not `Unit` - so a call site that switches which scope it mounts
    // (e.g. a tab switcher passing a different `name` without wrapping in `key()`) correctly
    // tears down the old scope and resolves the new one against its *current* `parameters`,
    // instead of silently keeping the scope (and params) captured on first mount.
    val scope = rememberOrLoadScope(name, holder, instance) {
        holder.findOrLoad(name) {
            (parameters ?: emptyMap()) + saver.savedParams
        }
    }

    LaunchedEffect(instance) {
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

    DisposableEffect(instance) {
        scope + LifecycleEvents.VISIBLE
        onDispose {
            scope + LifecycleEvents.DISPOSED
        }
    }

    CompositionLocalProvider(LocalScope provides scope) {
        content()
    }
}

/** Composition root for this library: provides a freshly built [ScopeHolder] (from [block]) as
 * [LocalScopeHolder], plus a fresh [ScopeCounter] and [DefaultStateSaver], for [content] and
 * everything below it. Call once near the top of the app. */
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