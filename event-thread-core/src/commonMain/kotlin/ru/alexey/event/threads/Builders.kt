package ru.alexey.event.threads

import ru.alexey.event.threads.EventBus.Companion.defaultFactory
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.resources.ResourcesFactory
import ru.alexey.event.threads.scopeholder.KeyHolder
import ru.alexey.event.threads.datacontainer.ContainerBuilder
import ru.alexey.event.threads.emitter.Emitter
import ru.alexey.event.threads.emitter.EmittersBuilder
import ru.alexey.event.threads.resources.ResourceProvider
import kotlin.random.Random
import kotlin.reflect.KClass

class ScopeBuilder(
    private var name: String,
    private val resources: ResourcesFactory = ResourcesFactory()
) : ResourceProvider by resources {


    val scope: Scope
            by lazy {
                with(ConfigBuilder()){

                    configs()

                    object : Scope(resources) {
                        override val key: String = name
                        override val eventBus: EventBus = this@with()
                        override fun <T : Any> get(clazz: KClass<T>): Datacontainer<T>? = containerBuilder[clazz]

                        init {
                            applied.forEach {
                                it()
                            }
                            emitters = emittersBuilder.build(this)
                        }
                    }.apply {
                        containerBuilder.mutex.unlock()
                    }
                }
            }

    private var configs: ConfigBuilder.() -> Unit = {}
    private val containerBuilder = ContainerBuilder()
    private val applied = mutableListOf<Scope.() -> Unit>()
    private val emittersBuilder = EmittersBuilder()

    @Builder
    fun config(block: ConfigBuilder.() -> Unit) {
        configs = block
    }

    @Builder
    fun containers(block: ContainerBuilder.() -> Unit) {
        containerBuilder.apply(block)
    }

    @Builder
    fun resources(block: ResourcesFactory.() -> Unit) {
        resources.apply(block)
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
}

class ConfigBuilder {
    private var eventBus: EventBus = defaultFactory()
    @Builder
    fun createEventBus(block: EventBussBuilder.() -> Unit) {
        eventBus = with(EventBussBuilder().also(block)) { build() }
    }

    operator fun invoke() = eventBus
}


abstract class Scope(
    resources: ResourcesFactory
) : KeyHolder, ResourceProvider by resources {

    abstract val eventBus: EventBus
    protected lateinit var emitters: List<Emitter<out Event>>
    abstract operator fun <T : Any> get(clazz: KClass<T>): Datacontainer<T>?
    inline fun <reified T : Any> resolve(): Datacontainer<T>? = get(T::class)
    inline fun <reified T : Any> resolveOrThrow(): Datacontainer<T> =
        get(T::class) ?: throw Exception("Container not registered")

    operator fun plus(event: Event) = eventBus + event

    @Builder
    inline infix fun <reified T : Event, reified TYPE : Any> EventThread<T>.bind(noinline action: suspend EventBus.(TYPE, T) -> TYPE): EventThread<T> {
        invoke { event ->
            if (event is T) {
                with(eventBus) {
                    resolve<TYPE>()?.value?.let { type: TYPE ->
                        val new = action(type, event)
                        resolve<TYPE>()?.update { new }
                    }
                }
            }
        }
        return this
    }

    @Builder
    inline infix fun <reified T : Event, reified TYPE : Any> EventThread<T>.tap(noinline action: suspend EventBus.(TYPE, T) -> Unit): EventThread<T> {
        invoke { event ->
            if (event is T) {
                with(eventBus) {
                    resolve<TYPE>()?.value?.also { type: TYPE ->
                        this.action(type, event)
                    }
                }
            }
        }
        return this
    }

    @Builder
    inline infix fun <reified T : Event, reified OTHER : Event> EventThread<T>.trigger(
        crossinline factory: suspend (T) -> OTHER
    ): EventThread<T> {
        invoke { event ->
            if (event is T) {
                eventBus + factory(event)
            }
        }

        return this
    }


    @Builder
    inline fun <reified T : Event> eventThread(noinline action: suspend EventBus.(T) -> Unit): EventThread<T> {

        return object : EventThread<T>() {
            override fun close() {
                eventBus.unsubscribe<T>()
            }

            init {
                val eventBus = eventBus
                invoke {
                    if (it is T) {
                        with(eventBus) {
                            action(it)
                        }
                    }
                }
                eventBus { this }
            }
        }
    }

    @Builder
    inline fun <reified T : Event> eventThread(): EventThread<T> {

        return object : EventThread<T>() {
            override fun close() {
                eventBus.unsubscribe<T>()
            }

            init {
                eventBus { this }
            }
        }
    }

    inline fun <reified T : Any> resource() = resource(T::class)



    fun <T: Any> resource(clazz: KClass<T>, block: MutableMap<KClass<out Any>, () -> Any>.() -> Unit)
     = resource(
        clazz,
        buildMap { apply(block) }
    )
    inline fun <reified T : Any> resource(noinline block: MutableMap<KClass<out Any>, () -> Any>.() -> Unit) =
        resource(T::class, block)


    inline fun <reified T : Any> MutableMap<KClass<out Any>, () -> Any>.param(noinline block: () -> T) {
        put(T::class, block)
    }
}

@Builder
fun scopeBuilder(keyHolder: KeyHolder? = null, block: ScopeBuilder.() -> Unit): Scope =
    scopeBuilder(keyHolder?.key, block)

@Builder
fun scopeBuilder(name: String? = null, block: ScopeBuilder.() -> Unit): Scope {
    val scope = ScopeBuilder(
        name ?: Random.Default.nextBytes(132).toString()
    ).apply { block() }

    return scope.scope
}

@DslMarker
annotation class Builder

