package ru.alexey.event.threads.datacontainer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import ru.alexey.event.threads.resources.ObservableResource
import kotlin.properties.ReadOnlyProperty


/**
 * A [StateFlow] of scope-local state that can also be updated in place. Registered on a [ru.
 * alexey.event.threads.Scope] via `datacontainer { }`/`transform { }` (see `ScopeBuilder`'s
 * `datacontainer` builders in `datacontainer/Builders.kt`) and resolved back via
 * [ru.alexey.event.threads.Scope.resolve]/[ru.alexey.event.threads.Scope.resolveOrThrow]. Collect
 * it like any [StateFlow]; mutate it only through [update].
 */
interface Datacontainer<T> : StateFlow<T> {
    /**
     * Replaces the current value with `block(currentValue)`.
     *
     * Not reentrant: calling [update] again on the same container from within [block]
     * (synchronously, on the same coroutine) will hang.
     */
    suspend fun update(block: suspend (T) -> T)
}

/** Base [Datacontainer] implementation backing a plain (non-transformed) `datacontainer { }`
 * registration - see [realDataContainer]. */
abstract class RealDataContainer<T>(
    stateFlow: StateFlow<T>
) : StateFlow<T> by stateFlow, Datacontainer<T>


// `scope` here is expected to already be a container-owned scope (see
// `ScopeBuilder.datacontainer` in `datacontainer/Builders.kt`, the only caller): `close()`
// below cancels it directly, so passing in a scope the caller still needs elsewhere - their own
// `viewModelScope`, say - would take it down entirely the moment this one container is closed.
@OptIn(ExperimentalStdlibApi::class)
inline fun<reified T: Any> ContainerBuilder.realDataContainer(
    flow: StateFlow<T>, scope: CoroutineScope , crossinline innerUpdate: suspend (suspend (T) -> T) -> Unit
): RealDataContainer<T> = object : AutoCloseable, RealDataContainer<T>(
    flow
) {

    override suspend fun update(block: suspend (T) -> T) {
        innerUpdate(block)
    }

    init {
        this@realDataContainer[T::class] = this as Datacontainer<T>
        launchIn(scope)
    }

    override fun close() {
        scope.cancel()
    }
}




