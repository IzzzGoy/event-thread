package ru.alexey.event.threads.scopeholder.typed

import ru.alexey.event.threads.EventThreadAction
import ru.alexey.event.threads.EventThreadMetadataBuilder
import ru.alexey.event.threads.EventType
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.EventBus
import ru.alexey.event.threads.bus.ValidatingEventBus
import ru.alexey.event.threads.datacontainer.ContainerBuilder
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass

/**
 * Receiver of [ScopeTyped.wire]: registers a typed scope's datacontainers ([container]) and event
 * handlers ([on]). Both are `inline reified`, so the container's value type and the handler's
 * event type come from the declaration itself - generated code never spells out a `KClass`.
 */
class ScopeWiring internal constructor(
    internal val eventBus: EventBus,
    private val dependencyProvider: DependencyProvider,
    /** The scope's params - passed to every container/handler resolution after its `Deps`, so a
     * binding can use them (`(params: TodoListParams) -> TodosContainerImpl(params.cacheKey)`).
     * Not passed when `Unit`. */
    private val params: Any = Unit,
) {
    @PublishedApi
    internal val containers = ContainerBuilder()

    /** Handler types [handler] couldn't resolve - reported together once [ScopeTyped.wire] ends. */
    internal val missingHandlers = mutableListOf<String>()

    /** Registers [container] under its value type and [name], so `scope.resolve<T>(name)` and
     * Compose's container lookups find it, and returns it for handler [handler] deps.
     * Register containers before the handlers using them. */
    inline fun <reified T : Any, C : Datacontainer<T>> container(name: String, container: C): C {
        containers.set(T::class, name, container)
        return container
    }

    /**
     * Resolves the implementation of container [C] (a generated abstract container - the spec
     * can't say what its initial state is, the binding's `source` does) from the scope's
     * [DependencyProvider], and registers it like [container]. Fails immediately when unbound:
     * every handler using it needs the real instance in its `Deps`. Bind as a factory - each scope
     * instance owns its containers.
     */
    inline fun <reified T : Any, reified C : Datacontainer<T>> container(name: String, deps: Any? = null): C =
        container<T, C>(name, resolveContainer(C::class, deps))

    @PublishedApi
    internal fun <C : Any> resolveContainer(type: KClass<C>, deps: Any?): C =
        dependencyProvider.getOrNull(type, null, parametersOf(deps))
            ?: error("No implementation registered in the scope's DependencyProvider for container ${type.simpleName}")

    /** `Deps` first, then the scope's params: Koin binds by position, so a binding destructures
     * `(deps)`, `(deps, params)`, or `(params)` for a dependency-less one; DummyProvider by type. */
    private fun parametersOf(deps: Any?): Parameters = buildMap {
        if (deps != null) put(deps::class) { deps }
        if (params != Unit) put(params::class) { params }
    }

    /**
     * Resolves the implementation of [H] (a generated handler base, e.g. `AddTodoStateProcessor`)
     * from the scope's [DependencyProvider] and subscribes it to [E]. [deps] - the base's
     * generated `Deps` (containers, snapshots) - is passed as the one resolution parameter:
     * Koin `factory<AddTodoStateProcessor> { (deps: AddTodoStateProcessor.Deps) -> AddTodoImpl(deps) }`,
     * DummyProvider `register<AddTodoStateProcessor> { AddTodoImpl(it.resolve()) }`.
     *
     * Register handlers as factories, not singletons: each scope instance needs its own (a cascader
     * can belong to one scope only, and a processor must update this instance's container).
     */
    inline fun <reified E : Event, reified H : EventHandler<E>> handler(deps: Any? = null) {
        resolveHandler(E::class, H::class, deps)
    }

    @PublishedApi
    internal fun <E : Event, H : EventHandler<E>> resolveHandler(event: KClass<E>, type: KClass<H>, deps: Any?) {
        val parameters = parametersOf(deps)
        val handler = dependencyProvider.getOrNull(type, null, parameters)
        if (handler == null) missingHandlers += type.simpleName.orEmpty() else register(event, handler)
    }

    /**
     * Subscribes [handler] to [E] on this scope's bus. Several handlers may subscribe to the same
     * event - they run in registration order, same as several `thread<E>()` registrations.
     *
     * Constructor-injected handlers and containers are built separately, so nothing at compile
     * time stops a processor from getting a *different* instance of the right container class -
     * its writes would then land somewhere this scope never exposes. That's checked here instead,
     * failing on first use of the scope rather than as silently stale state.
     */
    inline fun <reified E : Event> on(handler: EventHandler<E>) {
        register(E::class, handler)
    }

    @PublishedApi
    internal fun <E : Event> register(type: KClass<E>, handler: EventHandler<E>) {
        when (handler) {
            is EventHandler.StateProcessor<*, *> -> check(containers.containersEntries.values.any { it === handler.datacontainer }) {
                "${handler::class.simpleName} updates a container that isn't registered on this scope - " +
                    "pass it the same instance the scope gets (and register containers before handlers)"
            }
            is EventHandler.EventCascader<*> -> handler.attach(eventBus)
            is EventHandler.SideEffect<*> -> Unit
        }
        // Anonymous handler objects have no class name - fall back to the event's.
        val description = handler::class.simpleName ?: type.simpleName.orEmpty()
        val thread = EventThreadMetadataBuilder<E>(description, handler.privacy, handler.override).apply {
            if (handler is EventHandler.EventCascader<*>) {
                output(*handler.outputs.toTypedArray())
                onMismatch(handler.onMismatch)
            }
        }.build()

        val action: suspend (E) -> Unit = when (handler) {
            // Same enforcement as a DSL `.then { }` with output<>() declared: pushes go through a
            // ValidatingEventBus built per dispatch (it reports against the triggering event).
            is EventHandler.EventCascader<E> -> { event ->
                val declared = thread.declaredOutputs
                val bus = if (declared == null) eventBus else ValidatingEventBus(eventBus, description, event, declared)
                handler.cascadeInto(event, bus)
            }
            else -> { event -> handler.handle(event) }
        }
        // One action, so one producedType: exact when the cascader declares a single output. With
        // several, the graph's per-action edge can't name them all - allowedOutputs still does.
        val produced = (handler as? EventHandler.EventCascader<*>)?.outputs?.singleOrNull()
        thread(EventThreadAction(action, handler.eventType, produced))
        eventBus(type) { thread }
    }

    /** Same introspection tags the DSL's `.then`/`.end` steps carry (see [EventType]), so the
     * event-flow graph/metadata tooling reads typed scopes the same way. */
    private val EventHandler<*>.eventType: EventType
        get() = when (this) {
            is EventHandler.StateProcessor<*, *> -> EventType.modification
            is EventHandler.EventCascader<*> -> EventType.cascade
            is EventHandler.SideEffect<*> -> EventType.consume
        }
}
