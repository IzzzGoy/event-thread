package ru.alexey.event.threads.scopeholder.typed

import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.bus.EventBus
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.DummyProvider
import ru.alexey.event.threads.di.Qualifier
import kotlin.reflect.KClass

/**
 * Class-based [Scope] - the typed-layer counterpart of `scopeBuilder { }`. Containers are named
 * classes the scope creates itself; handlers are named abstract bases whose implementations come
 * from the [dependencyProvider] (see [ScopeWiring.handler]); [wire] connects them:
 * ```
 * // generated
 * class TodosContainer : DatacontainerTyped<List<Todo>>() {
 *     override val source = flowResource(emptyList<Todo>())
 * }
 * abstract class AddTodoStateProcessor(deps: Deps) : EventHandler.StateProcessor<AddTodo, List<Todo>>(deps.todos) {
 *     class Deps(val todos: TodosContainer)
 * }
 * object TodoScopeKey : ScopeKey<TodoListParams> { override val identity = "Todo" }
 * data class TodoListParams(val listName: String)
 *
 * class TodoScope(params: TodoListParams, dependencyProvider: DependencyProvider) :
 *     ScopeTyped<TodoListParams>(TodoScopeKey, params, dependencyProvider = dependencyProvider) {
 *     override fun ScopeWiring.wire() {
 *         val todos = container("todos", TodosContainer())
 *         handler<AddTodo, AddTodoStateProcessor>(AddTodoStateProcessor.Deps(todos))
 *     }
 * }
 *
 * // hand-written
 * class AddTodoImpl(deps: Deps) : AddTodoStateProcessor(deps) {
 *     override suspend fun process(event: AddTodo, state: List<Todo>) = state + Todo(event.text)
 * }
 * val todoModule = module { factory<AddTodoStateProcessor> { (deps: AddTodoStateProcessor.Deps) -> AddTodoImpl(deps) } }
 * ```
 * Compile-time checked down to the container: a handler base's `Deps` names the exact container
 * classes it writes to and reads ([ContainerSnapshot]), and the scope builds every `Deps` from its
 * own containers. A DI binding that ignored its `Deps` and used another container instance would
 * still compile; [ScopeWiring.on] catches that at wiring. Missing bindings are reported together,
 * on the scope's first use.
 *
 * [wire] runs once, lazily, on the scope's first use ([eventBus], [get], [close] - every way in),
 * not from this constructor: it reads the subclass's constructor properties, which aren't
 * assigned yet while this base constructor runs. Corollary: don't touch [eventBus] or the
 * containers from a subclass's `init`/property initializers.
 */
abstract class ScopeTyped<P : Any>(
    /** This scope's typed identity - `key` is its [ScopeKey.identity]. */
    val scopeKey: ScopeKey<P>,
    /** What this instance was created with - for a per-item scope, the item. `Unit` for none. */
    val params: P,
    final override val description: String = "",
    final override val dependencyProvider: DependencyProvider = DummyProvider(),
    /** Builds this scope's bus; returning `null` (or omitting it) falls back to an [EventBus]
     * bound in [dependencyProvider] under this scope's key as qualifier
     * (`factory<EventBus>(named("TodoDraft")) { ... }` - a scope needing a main-thread bus, or an
     * event-logging watcher), else [EventBus.defaultFactory]. */
    private val eventBusFactory: (() -> EventBus?)? = null,
) : Scope() {

    final override val key: String get() = scopeKey.identity

    /** Registers this scope's containers and handlers - see [ScopeWiring]. Called exactly once. */
    protected abstract fun ScopeWiring.wire()

    // Synchronized (the `lazy` default): a scope can be first touched from the UI thread and from
    // ScopeHolder's routing coroutine at once, and must wire exactly once.
    private val wiringLazy = lazy {
        emitters = emptyList()
        val bus = eventBusFactory?.invoke()
            ?: dependencyProvider.getOrNull(EventBus::class, Qualifier.named(scopeKey.identity))
            ?: EventBus.defaultFactory()
        ScopeWiring(bus, dependencyProvider, params).apply {
            wire()
            // All at once rather than at the first miss: a DI module missing bindings is usually
            // missing several, and fixing them one failed launch at a time is miserable.
            check(missingHandlers.isEmpty()) {
                "Scope '$key': no implementation registered in its DependencyProvider for $missingHandlers"
            }
        }
    }
    private val wiring: ScopeWiring by wiringLazy

    final override val eventBus: EventBus get() = wiring.eventBus

    final override fun <T : Any> get(clazz: KClass<T>, name: String?): Datacontainer<T>? =
        wiring.containers.get(clazz, name)

    /**
     * The container registered in [wire] under [name], as its own class - what generated scopes
     * expose as typed properties (`workScope.todos: TodosContainer`). Wires the scope on first
     * use, like every other way in.
     */
    protected fun <C : Datacontainer<*>> typedContainer(name: String): C {
        val container = checkNotNull(wiring.named[name]) { "Scope '$key' has no container '$name'" }
        @Suppress("UNCHECKED_CAST")
        return container as C
    }

    /** Closes every registered container and the bus. A scope that was never used has nothing to
     * close, so this doesn't wire it just to tear it down. */
    override fun close() {
        if (!wiringLazy.isInitialized()) return
        wiring.containers.containersEntries.values.forEach { (it as? AutoCloseable)?.close() }
        super.close()
    }
}
