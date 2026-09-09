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

    fun build(): Scope = buildScope(buildConfig())

    @Builder
    fun config(block: ConfigBuilder.() -> Unit) {
        configs += block
    }

    @Builder
    fun threads(block: Scope.() -> Unit) {
        applied.add { block() }
    }

    @Builder
    fun name(block: () -> String) {
        name = block()
    }

    @Builder
    fun emitters(block: EmittersBuilder.() -> Unit) {
        emittersBuilder.apply(block)
    }

    fun apply(scopeBuilder: ScopeBuilder) {
        applied += scopeBuilder.applied
        containerBuilder.apply(scopeBuilder.containerBuilder.containersEntries)
        configs += scopeBuilder.configs
        emittersBuilder.merge(scopeBuilder.emittersBuilder)
    }
}

class ConfigBuilder {
    // One accumulating builder, not "replace with a freshly built EventBus on every call": with
    // `ScopeBuilder.configs` now a list (see above), a base scope's `createEventBus { onError {
    // } }` and a scope that `implements` it both run against this same ConfigBuilder - the
    // implementing scope's own `createEventBus { watcher { } }` now adds to the base's
    // interceptors/error handlers instead of throwing them away by building a brand new bus.
    private val eventBusBuilder = EventBusBuilder()
    private val eventBus by lazy { eventBusBuilder.build() }
    private var description: String = ""

    @Builder
    fun createEventBus(block: EventBusBuilder.() -> Unit) {
        eventBusBuilder.apply(block)
    }

    fun description(block: () -> String) {
        description = block()
    }

    operator fun invoke() = eventBus

    fun build() = ScopeConfig(
        eventBus, description
    )
}

class ScopeConfig(val eventBus: EventBus, val description: String)
class ScopeMetadata(val description: String, val eventsMetadata: Map<String, EventThreadInfo>)

@OptIn(ExperimentalStdlibApi::class)
abstract class Scope : KeyHolder, AutoCloseable {

    abstract val eventBus: EventBus
    abstract val description: String
    abstract val dependencyProvider: DependencyProvider
    val metadata
        get() = ScopeMetadata(description, eventBus.metadata)
    protected lateinit var emitters: List<Emitter<out Event>>

    override fun close() {
        eventBus.close()
    }
    abstract operator fun <T : Any> get(clazz: KClass<T>): Datacontainer<T>?
    inline fun <reified T : Any> resolve(): Datacontainer<T>? = get(T::class)
    inline fun <reified T : Any> resolveOrThrow(): Datacontainer<T> =
        get(T::class) ?: throw Exception("Container not registered")

    operator fun <T : Any> get(datacontainerKey: DatacontainerKey<T>): Datacontainer<T> {
        return this[datacontainerKey.keyKClass] ?: throw Exception("Container not registered")
    }


    inline operator fun<reified T: Any> getValue(thisRef: Any?, property: KProperty<*>): Datacontainer<T> {
        return resolveOrThrow()
    }

    operator fun plus(event: Event) {
        eventBus += event
    }


    inline fun <reified T : Event> thread(block: EventThreadMetadataBuilder<T>.() -> Unit): EventThread<T> {
        return EventThreadMetadataBuilder<T>().apply(block).build().also { eventBus { it } }
    }

    inline fun <reified T : Event> thread(): EventThread<T> {
        return EventThreadMetadataBuilder<T>().build().also { eventBus { it } }
    }

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

@Builder
inline fun scopeBuilder(
    keyHolder: KeyHolder? = null,
    parents: List<ScopeBuilder> = emptyList(),
    dependencyProvider: DependencyProvider = DummyProvider(),
    noinline block: ScopeBuilder.(Parameters) -> Unit
): (Parameters) -> ScopeBuilder =
    scopeBuilder(keyHolder?.key, parents, dependencyProvider, block)

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

@DslMarker
annotation class Builder


