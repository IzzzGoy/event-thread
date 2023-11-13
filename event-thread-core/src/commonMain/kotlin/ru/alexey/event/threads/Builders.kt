package ru.alexey.event.threads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import ru.alexey.event.threads.EventBus.Companion.defaultFactory
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.resources.ResourcesBuilder
import ru.alexey.event.threads.scopeholder.KeyHolder
import kotlin.random.Random
import kotlin.reflect.KClass

class ScopeBuilder(
    private var name: String
) {
    fun name(block: () -> String) {
        name = block()
    }

    val scope: Scope
            by lazy {
                ConfigBuilder().let { builder ->
                    configs.values.forEach {
                        builder.apply(it)
                    }

                    object : Scope() {
                        override val key: String = name
                        override val eventBus: EventBus = builder.eventBus
                        override fun <T : Any> get(clazz: KClass<T>): Datacontainer<T>? {
                            return (containerBuilder.containers[clazz])?.let { it as Datacontainer<T> }
                        }
                        init {
                            applied.forEach {
                                with(this) {
                                    it()
                                }
                            }
                        }
                    }.apply {
                        containerBuilder.mutex.unlock()
                    }
                }
            }

    val configs = mutableMapOf<String, ConfigBuilder.() -> Unit>()
    val containerBuilder = ContainerBuilder()
    val applied = mutableListOf<Scope.() -> Unit>()
    val resources = ResourcesBuilder()
}
class ConfigBuilder {

    var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    var eventBus: EventBus = defaultFactory()
}
inline fun ScopeBuilder.config(noinline block: ConfigBuilder.() -> Unit) {
    configs.put("config", block)
}


class ContainerBuilder {
    val containers: MutableMap<KClass<out Any>, Datacontainer<out Any>>
            = mutableMapOf()
    val mutex = Mutex(true)
}



inline fun ScopeBuilder.containers(noinline block: ContainerBuilder.() -> Unit) {
    containerBuilder.apply { block() }
}
inline fun ScopeBuilder.threads(noinline block: Scope.() -> Unit) {
    applied.add { block() }
}
abstract class Scope: KeyHolder {

    abstract val eventBus: EventBus
    abstract operator fun<T: Any> get(clazz: KClass<T>): Datacontainer<T>?
    inline fun <reified T: Any> resolve(): Datacontainer<T>? = get(T::class)
    inline fun <reified T: Any> resolveOrThrow(): Datacontainer<T> = get(T::class) ?: throw Exception("Container not registered")

    operator fun plus(event: Event) = eventBus + event

    inline infix fun<reified T: Event, reified TYPE: Any> EventThread<T>.bind(noinline action: suspend EventBus.(TYPE, T) -> TYPE): EventThread<T> {
        invoke { event ->
            if (event is T) {
                with(eventBus) {
                    resolve<TYPE>()?.value?.let {  type: TYPE ->
                        val new = action(type, event)
                        resolve<TYPE>()?.update { new }
                    }
                }
            }
        }
        return this
    }

    inline infix fun<reified T: Event, reified TYPE: Any> EventThread<T>.tap(noinline action: suspend EventBus.(TYPE, T) -> Unit): EventThread<T> {
        invoke { event ->
            if (event is T) {
                with(eventBus) {
                    resolve<TYPE>()?.value?.also {  type: TYPE ->
                        this.action(type, event)
                    }
                }
            }
        }
        return this
    }

            inline infix fun<reified T: Event, reified OTHER: Event> EventThread<T>.trigger(
        crossinline factory: suspend (T) -> OTHER
    ): EventThread<T> {
        invoke { event ->
            if (event is T) {
                eventBus + factory(event)
            }
        }

        return this
    }



    inline fun<reified T: Event> eventThread(noinline action: suspend EventBus.(T) -> Unit) : EventThread<T> {

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

    inline fun<reified T: Event> eventThread() : EventThread<T> {

        return object : EventThread<T>() {
            override fun close() {
                eventBus.unsubscribe<T>()
            }
            init {
                eventBus { this }
            }
        }
    }

    inline fun<reified T: Any> ScopeBuilder.resource() = resources.resolve<T>()
    inline fun<reified T: Any> ScopeBuilder.resource(block: MutableMap<KClass<out Any>, () -> Any>.() -> Unit)
            = resources.resolve<T>(
        buildMap { apply(block) }
    )
    inline fun<reified T: Any> MutableMap<KClass<out Any>, () -> Any>.param(noinline block: () -> T) {
        put(T::class, block)
    }
}

@Builder
fun scopeBuilder(keyHolder: KeyHolder? = null, block: ScopeBuilder.() -> Unit): Scope {
    val scope = ScopeBuilder(
        keyHolder?.key ?: Random.Default.nextBytes(132).toString()
    ).apply { block() }

    return scope.scope
}

@DslMarker
annotation class Builder

