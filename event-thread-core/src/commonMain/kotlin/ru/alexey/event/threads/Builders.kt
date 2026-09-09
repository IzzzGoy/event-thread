package ru.alexey.event.threads

import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.EventBus
import ru.alexey.event.threads.bus.EventBusBuilder
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.DummyProvider
import ru.alexey.event.threads.scopeholder.KeyHolder
import ru.alexey.event.threads.datacontainer.ContainerBuilder
import ru.alexey.event.threads.datacontainer.DatacontainerKey
import ru.alexey.event.threads.emitter.Emitter
import ru.alexey.event.threads.emitter.EmittersBuilder
import ru.alexey.event.threads.resources.Parameters
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * Declarative builder for a single [Scope]: register its event threads ([threads]), derived
 * containers (via [containerBuilder]), reactive sources ([emitters]) and event-bus configuration
 * ([config]) before calling [build] to get the live [Scope].
 *
 * Normally created via [scopeBuilder] rather than directly - that's what wires it into a
 * [ru.alexey.event.threads.scopeholder.ScopeHolder] under a name and lets other scopes
 * `implements`/`dependsOn` it. [parents] is how `implements` is implemented: each parent's
 * config/threads/containers/emitters are copied into this builder (see [apply]) before this
 * builder's own `block` runs, so a child's own registrations can add to - or, with
 * `override = true`, replace - what it inherited.
 */
class ScopeBuilder(
    private var name: String,
    parents: List<ScopeBuilder> = emptyList(),
    val dependencyProvider: DependencyProvider = DummyProvider()
) {
    // A list, not a single replaced lambda: `apply(parent)` below appends the parent's config
    // blocks here too, so a base scope's `config { createEventBus { watcher { } } }` still runs
    // for a scope that `implements` it, instead of being silently dropped the moment the child
    // declares its own `config { }` (which used to just overwrite this field).
    private val configs = mutableListOf<ConfigBuilder.() -> Unit>()
    val containerBuilder = ContainerBuilder()
    private val applied = mutableListOf<Scope.() -> Unit>()
    private val emittersBuilder = EmittersBuilder()
    init {
        parents.forEach(::apply)
    }

    private fun buildConfig(): ScopeConfig {
        val configBuilder = ConfigBuilder()
        with(configBuilder) { configs.forEach { it() } }
        return configBuilder.build()
    }

    // A single build path for this anonymous Scope, called only from `build()` below - there used
    // to be a second, independent copy of it (an unused `val scope by lazy` exposing its own
    // build), and the two had drifted out of sync once (one had the containers-close-on-close
    // behavior, the other didn't notice for a while). Removed rather than kept in sync: nothing
    // in the repo ever called it.
    private fun buildScope(config: ScopeConfig): Scope = object : Scope() {
        override val key: String = name
        override val eventBus: EventBus = config.eventBus
        override val description: String = config.description
        override val dependencyProvider: DependencyProvider = this@ScopeBuilder.dependencyProvider
        override fun <T : Any> get(clazz: KClass<T>): Datacontainer<T>? = containerBuilder[clazz]

        init {
            applied.forEach { it() }
            emitters = emittersBuilder.build(this)
        }

        override fun close() {
            containerBuilder.containersEntries.values.forEach { (it as? AutoCloseable)?.close() }
            super.close()
        }
    }

    /** Builds the live [Scope] this builder describes. Safe to call more than once; each call
     * produces its own independent [Scope]/[EventBus] instance. */
    fun build(): Scope = buildScope(buildConfig())

    /** Configures this scope's [EventBus] - watchers, error handlers, description. See
     * [ConfigBuilder]/[EventBusBuilder]. Can be called more than once (including via inherited
     * [apply]); every call's registrations accumulate onto the same underlying bus. */
    @Builder
    fun config(block: ConfigBuilder.() -> Unit) {
        configs += block
    }

    /** Registers this scope's event threads (`thread<T>().end { }`, `.then { }`, etc.) and any
     * other one-time setup that needs a live [Scope] receiver. */
    @Builder
    fun threads(block: Scope.() -> Unit) {
        applied.add { block() }
    }

    /** Overrides the scope's name after construction (e.g. to compute it from [Parameters]
     * only available inside the builder block). */
    @Builder
    fun name(block: () -> String) {
        name = block()
    }

    /** Registers reactive event sources for this scope - flows collected into its bus once it's
     * built. See [EmittersBuilder]. */
    @Builder
    fun emitters(block: EmittersBuilder.() -> Unit) {
        emittersBuilder.apply(block)
    }

    /**
     * Copies [scopeBuilder]'s own threads/containers/config/emitters into this builder - the
     * mechanism behind `implements`. Called once per direct parent from [init]; a parent's own
     * inherited content is *not* re-copied through it (see
     * [ru.alexey.event.threads.scopeholder.ScopeHolder]'s `getAllDeps`), so a diamond-shaped
     * `implements` graph still applies a shared ancestor's registrations exactly once.
     */
    fun apply(scopeBuilder: ScopeBuilder) {
        applied += scopeBuilder.applied
        containerBuilder.apply(scopeBuilder.containerBuilder.containersEntries)
        configs += scopeBuilder.configs
        emittersBuilder.merge(scopeBuilder.emittersBuilder)
    }
}

/** Receiver for [ScopeBuilder.config] - builds the [EventBus] and description for one [Scope]. */
class ConfigBuilder {
    // One accumulating builder, not "replace with a freshly built EventBus on every call": with
    // `ScopeBuilder.configs` now a list (see above), a base scope's `createEventBus { onError {
    // } }` and a scope that `implements` it both run against this same ConfigBuilder - the
    // implementing scope's own `createEventBus { watcher { } }` now adds to the base's
    // interceptors/error handlers instead of throwing them away by building a brand new bus.
    private val eventBusBuilder = EventBusBuilder()
    private val eventBus by lazy { eventBusBuilder.build() }
    private var description: String = ""

    /** Configures the [EventBus] this scope will use - watchers, `onError` handlers. See
     * [EventBusBuilder]. */
    @Builder
    fun createEventBus(block: EventBusBuilder.() -> Unit) {
        eventBusBuilder.apply(block)
    }

    /** Sets this scope's human-readable [ScopeMetadata.description], surfaced for debugging/
     * introspection - has no effect on dispatch or event handling. */
    fun description(block: () -> String) {
        description = block()
    }

    operator fun invoke() = eventBus

    fun build() = ScopeConfig(
        eventBus, description
    )
}

/** The built products of [ConfigBuilder]: the [Scope]'s [eventBus] and its [description]. */
class ScopeConfig(val eventBus: EventBus, val description: String)

/** Introspection snapshot of a [Scope] - its [description] and its bus's registered event
 * threads ([eventsMetadata], keyed by event class name). See [Scope.metadata]. */
class ScopeMetadata(val description: String, val eventsMetadata: Map<String, EventThreadInfo>)

/**
 * A live, named unit of state and behavior: owns an [eventBus], a set of [Datacontainer]s
 * (resolved via [get]/[resolve]/[resolveOrThrow]), and optional [emitters] feeding external
 * events into that bus. Built from a [ScopeBuilder] (usually via [scopeBuilder] +
 * [ru.alexey.event.threads.scopeholder.ScopeHolder]), not instantiated directly.
 *
 * Dispatch an event into this scope's bus with [plus] (`scope + MyEvent`); declare handlers for
 * events with [thread] and its `.then { }`/`.end { }` extensions. Call [close] (or let
 * [ru.alexey.event.threads.scopeholder.ScopeHolder.free] do it) when the scope is no longer
 * needed - it cancels the event bus and closes every [AutoCloseable] container.
 */
@OptIn(ExperimentalStdlibApi::class)
abstract class Scope : KeyHolder, AutoCloseable {

    abstract val eventBus: EventBus
    abstract val description: String
    abstract val dependencyProvider: DependencyProvider

    /** Introspection snapshot of this scope's [description] and registered event threads. */
    val metadata
        get() = ScopeMetadata(description, eventBus.metadata)
    protected lateinit var emitters: List<Emitter<out Event>>

    /** Closes this scope's [eventBus] and every registered [AutoCloseable] container. Prefer
     * going through [ru.alexey.event.threads.scopeholder.ScopeHolder.free] over calling this
     * directly, so dependent scopes are freed in the right order too. */
    override fun close() {
        eventBus.close()
    }

    /** Looks up the [Datacontainer] registered for [clazz] on this scope, or `null` if none is. */
    abstract operator fun <T : Any> get(clazz: KClass<T>): Datacontainer<T>?

    /** [get] by reified type - `null` if [T] has no container registered on this scope. */
    inline fun <reified T : Any> resolve(): Datacontainer<T>? = get(T::class)

    /** [resolve], throwing if [T] has no container registered on this scope - use when the
     * container's presence is a build-time invariant of this scope, not something to branch on. */
    inline fun <reified T : Any> resolveOrThrow(): Datacontainer<T> =
        get(T::class) ?: throw Exception("Container not registered")

    /** [get] via a [DatacontainerKey] instead of a raw [KClass] - throws if unregistered, same as
     * [resolveOrThrow]. */
    operator fun <T : Any> get(datacontainerKey: DatacontainerKey<T>): Datacontainer<T> {
        return this[datacontainerKey.keyKClass] ?: throw Exception("Container not registered")
    }

    /** Property-delegate form of [resolveOrThrow] - `val todos: Datacontainer<List<Todo>> by
     * scope`. */
    inline operator fun<reified T: Any> getValue(thisRef: Any?, property: KProperty<*>): Datacontainer<T> {
        return resolveOrThrow()
    }

    /** Dispatches [event] into this scope's [eventBus]. */
    operator fun plus(event: Event) {
        eventBus += event
    }

    /** Registers (or looks up) this scope's handler for events of type [T], configuring it via
     * [block] (`.end { }`, `.then { }`, `override { }`, etc. - see [EventThreadMetadataBuilder]).
     * Calling this again for the same [T] on a live scope adds actions rather than replacing the
     * existing registration, unless `override = true` was set. */
    inline fun <reified T : Event> thread(block: EventThreadMetadataBuilder<T>.() -> Unit): EventThread<T> {
        return EventThreadMetadataBuilder<T>().apply(block).build().also { eventBus { it } }
    }

    /** [thread] with no extra configuration - typically followed by `.end { }`/`.then { }`. */
    inline fun <reified T : Event> thread(): EventThread<T> {
        return EventThreadMetadataBuilder<T>().build().also { eventBus { it } }
    }

    /** Chains a cascading step onto this thread: when [T] is dispatched, runs [factory] and
     * dispatches its result as a new event. Returns `this` so further `.then`/`.end` calls can
     * chain onto the same thread. */
    @Builder
    inline infix fun <reified T : Event, reified OTHER : Event> EventThread<T>.then(
        crossinline factory: suspend (T) -> OTHER
    ): EventThread<T> {
        val action = EventThreadActionBuilder<T>(EventType.cascade) {
            eventBus += factory(it)
        }
        invoke(action.build())
        return this
    }

    /** Chains a modification step onto this thread: when [T] is dispatched, updates
     * [datacontainer] by applying [factory] to its current value and the event. Returns `this`
     * so further `.then`/`.end` calls can chain onto the same thread. */
    @Builder
    inline fun <reified T : Event, reified TYPE : Any> EventThread<T>.then(
        datacontainer: Datacontainer<TYPE>,
        crossinline factory: suspend (TYPE, T) -> TYPE
    ): EventThread<T> {
        val action = EventThreadActionBuilder<T>(EventType.modification) {
            datacontainer.update { current -> factory(current, it) }
        }
        invoke(action.build())
        return this
    }

    /** Terminal step for this thread: when [T] is dispatched, runs [block] as a plain consuming
     * side effect - no further chaining, no new event dispatched. Returns `this`. */
    @Builder
    inline infix fun <reified T : Event> EventThread<T>.end(
        crossinline block: suspend (T) -> Unit
    ): EventThread<T> {
        val action = EventThreadActionBuilder<T>(EventType.consume) {
            block(it)
        }
        invoke(action.build())
        return this
    }

    @Deprecated("Use thread instead", ReplaceWith("thread<T>()"))
    @Builder
    inline fun <reified T : Event> eventThread(): EventThread<T> {

        return  EventThread<T>(
            EventMetadata("", Privacy.public)
        ).also {
            eventBus { it }
        }
    }

}

/** [scopeBuilder] taking a [KeyHolder] instead of a raw name - the resulting factory's name comes
 * from [KeyHolder.key]. */
@Builder
inline fun scopeBuilder(
    keyHolder: KeyHolder? = null,
    parents: List<ScopeBuilder> = emptyList(),
    dependencyProvider: DependencyProvider = DummyProvider(),
    noinline block: ScopeBuilder.(Parameters) -> Unit
): (Parameters) -> ScopeBuilder =
    scopeBuilder(keyHolder?.key, parents, dependencyProvider, block)

/**
 * Declares how to build a named [Scope]: returns a factory that, given [Parameters] at load
 * time, produces a fresh [ScopeBuilder] with [block] applied. [name] defaults to a random hex
 * string when omitted (an anonymous scope). [parents] is used directly by callers building a
 * scope by hand; when going through
 * [ru.alexey.event.threads.scopeholder.ScopeHolderBuilder.scopeEmbedded]/`implements`, the
 * parent list is resolved automatically instead.
 */
@OptIn(ExperimentalStdlibApi::class)
@Builder
fun scopeBuilder(
    name: String? = null,
    parents: List<ScopeBuilder> = emptyList(),
    dependencyProvider: DependencyProvider = DummyProvider(),
    block: ScopeBuilder.(Parameters) -> Unit
): (Parameters) -> ScopeBuilder {
    return {
        // `Random.nextBytes(132).toString()` (the old default) doesn't encode the bytes at all -
        // ByteArray.toString() prints the array's type and identity hash, e.g. "[B@1a2b3c4d",
        // which isn't derived from the random content and isn't a meaningful unique key. Encode
        // the bytes as hex instead, so an un-named anonymous scope actually gets a random name.
        ScopeBuilder(
            name ?: Random.nextBytes(16).toHexString(),
            parents,
            dependencyProvider
        ).apply { block(it) }
    }
}

/** Marks the scope/thread/emitter builder DSLs so nested lambdas can't accidentally call an
 * outer builder's methods (standard [DslMarker] scoping). */
@DslMarker
annotation class Builder


