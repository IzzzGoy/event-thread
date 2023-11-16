package ru.alexey.event.threads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.alexey.event.threads.datacontainer.Transform
import ru.alexey.event.threads.resources.ObservableResource

inline fun <T : Any> List<Transform<out Any, T>>.foldWithProxy(proxy: Flow<T>): Flow<T> =
    this.fold(proxy) { acc, (flow, transform) ->
        flow().combine(acc, transform)
    }

inline fun <T : Any> List<Transform<out Any, T>>.foldAndStateWithProxy(
    proxy: ObservableResource<T>,
    scope: CoroutineScope
) = this.foldWithProxy(proxy).stateIn(scope, SharingStarted.Lazily, proxy())