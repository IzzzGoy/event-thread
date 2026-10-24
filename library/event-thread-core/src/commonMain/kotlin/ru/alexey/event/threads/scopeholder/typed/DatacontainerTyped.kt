package ru.alexey.event.threads.scopeholder.typed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.datacontainer.Transform
import ru.alexey.event.threads.foldTransforms
import ru.alexey.event.threads.resources.ObservableResource

/**
 * Class-based [Datacontainer] - the typed-layer counterpart of `val x by datacontainer(source) { }`.
 * Declared as a solid class instead of a DSL block, so a container is a type of its own that
 * [EventHandler.StateProcessor]s and [ScopeTyped] fields can name (and codegen can emit an abstract
 * base for):
 * ```
 * class TodosContainer : DatacontainerTyped<List<Todo>>() {
 *     override val source = flowResource(emptyList<Todo>())
 * }
 *
 * class VisibleTodosContainer(
 *     private val todos: TodosContainer,
 *     private val showCompleted: ShowCompletedContainer,
 * ) : DatacontainerTyped<VisibleTodos>() {
 *     override val source = flowResource(VisibleTodos(emptyList()))
 *     override val transforms = listOf(
 *         transform(todos) { all, _ -> VisibleTodos(all.filter { showCompleted.value || !it.done }) },
 *         transform(showCompleted) { show, _ -> VisibleTodos(todos.value.filter { show || !it.done }) },
 *     )
 *     override fun onEach(value: VisibleTodos) = println(value)
 * }
 * ```
 * Same semantics as the DSL container: [update] writes through to [source] under a mutex,
 * [transforms] fold over it in declaration order, and the result is a hot [StateFlow].
 *
 * The pipeline is built on first use ([value], [collect], [update]), not in the constructor:
 * [source]/[transforms]/[onEach] are subclass members, and reading them from this base
 * constructor would see the subclass's own fields (the source, the containers a transform names)
 * still `null`.
 *
 * [coroutineScope] runs the pipeline. Like the DSL container, this works on a supervised child of
 * it, so [close] stops only this container - never the scope that was passed in.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
abstract class DatacontainerTyped<T : Any>(
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : Datacontainer<T>, AutoCloseable {

    /** Seeds the value and is what [update] writes through to. */
    protected abstract val source: ObservableResource<T>

    private val containerScope = CoroutineScope(
        coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job])
    )
    private val mutex = Mutex()

    /** Derived-state steps, folded over [source] in order - see [transform]. Default: none. */
    protected open val transforms: List<Transform<out Any, T>> get() = emptyList()

    /** Called with every new value - the typed counterpart of the DSL's `watcher { }`. For side
     * effects (logging, analytics); derive other state with [transforms] instead. */
    protected open fun onEach(value: T) {}

    /** One [transforms] step: whenever [other] emits, recompute this container's value from the
     * emitted value and the current one. */
    protected fun <O : Any> transform(other: Datacontainer<O>, block: suspend (O, T) -> T): Transform<O, T> =
        Transform(other = { other }, action = block)

    // Eagerly-shared: the pipeline (and so onEach) keeps running with no subscribers, same as the
    // DSL container's `stateIn(Lazily)` + permanent `launchIn` collector.
    private val state: StateFlow<T> by lazy {
        transforms.foldTransforms(source)
            .onEach { onEach(it) }
            .stateIn(containerScope, SharingStarted.Eagerly, source.value)
    }

    // Without transforms the state *is* the source - read it directly, so a write is visible to
    // the next read immediately (read-your-writes), instead of after the pipeline catches up on
    // its dispatcher: a handler checking this container right after another one updated it in
    // the same dispatch (a tally, then a limit check) must see the new value, not the previous.
    override val value: T get() = state.let { if (transforms.isEmpty()) source.value else it.value }

    override val replayCache: List<T> get() = state.replayCache

    override suspend fun collect(collector: FlowCollector<T>): Nothing = state.collect(collector)

    /** Not reentrant - calling [update] on this same container from inside [block] hangs (same as
     * the DSL container; see [Datacontainer.update]). */
    override suspend fun update(block: suspend (T) -> T) {
        // Start the pipeline first, so onEach observes this update even if nothing has read the
        // container yet.
        state
        mutex.withLock {
            val new = block(source.value)
            source.update { new }
        }
    }

    override fun close() {
        containerScope.cancel()
    }
}
