@file:Suppress("UNCHECKED_CAST")

package ru.alexey.event.threads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import ru.alexey.event.threads.datacontainer.Transform
import ru.alexey.event.threads.resources.ObservableResource

/** Chains this list of [Transform] steps into a single [Flow], starting from [proxy] and
 * `combine`-ing each transform's own source flow in registration order - the mechanism behind
 * [ru.alexey.event.threads.datacontainer.DatacontainerBuilder.transform]. Internal to the
 * `datacontainer { }` machinery; not meant to be called directly from app code. */
inline fun <reified T : Any> List<Transform<out Any, T>>.foldWithProxy(proxy: Flow<T>): Flow<T> =
    this.fold(proxy) { acc, transform ->
        val action = transform.action as suspend (Any, T) -> T
        transform.other().combine(acc) { a, b: T ->
            action(a, b)
        }
    }

/** [foldWithProxy], shared eagerly as a [kotlinx.coroutines.flow.StateFlow] on [scope] - backs a
 * plain (watcher-less) `datacontainer { }` registration. */
inline fun <reified T : Any> List<Transform<out Any, T>>.foldAndStateWithProxy(
    proxy: ObservableResource<T>,
    scope: CoroutineScope
) = this.foldWithProxy(proxy).stateIn(scope, SharingStarted.Lazily, proxy())

/** [foldAndStateWithProxy], additionally invoking every [watchers] callback on each new value -
 * backs `datacontainer { watcher { } }`. */
inline fun <reified T : Any> List<Transform<out Any, T>>.foldAndStateWithProxyAndWatchers(
    proxy: ObservableResource<T>,
    watchers: List<(T) -> Unit>,
    scope: CoroutineScope
) = this.foldWithProxy(proxy).onEach { state ->
    watchers.forEach { it(state) }
}.stateIn(scope, SharingStarted.Lazily, proxy())