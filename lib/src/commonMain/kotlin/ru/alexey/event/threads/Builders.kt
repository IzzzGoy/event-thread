package ru.alexey.event.threads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import ru.alexey.event.threads.EventBus.Companion.defaultFactory
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.resources.RecoursesBuilder
import ru.alexey.event.threads.resources.Resource
import kotlin.reflect.KClass

class ScopeEventsThreadBuilder {
    val config: Config
            by lazy {
                ConfigBuilder().let { builder ->
                    configs.values.forEach {
                        builder.apply(it)
                    }

                    object : Config() {
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
                    }.also {
                        containerBuilder.mutex.unlock()
                    }
                }
            }

    val configs = mutableMapOf<String, ConfigBuilder.() -> Unit>()
    val containerBuilder = ContainerBuilder()
    val applied = mutableListOf<Config.() -> Unit>()
    val resources = RecoursesBuilder()

}
class ConfigBuilder {

    var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    var eventBus: EventBus = defaultFactory()
}
inline fun ScopeEventsThreadBuilder.config(noinline block: ConfigBuilder.() -> Unit) {
    configs.put("config", block)
}


class ContainerBuilder {
    val containers: MutableMap<KClass<out Any>, Datacontainer<out Any>>
            = mutableMapOf()
    val mutex = Mutex(true)
}



inline fun ScopeEventsThreadBuilder.containers(noinline block: ContainerBuilder.() -> Unit) {
    containerBuilder.apply(block)
}
inline fun ScopeEventsThreadBuilder.threads(noinline block: Config.() -> Unit) {
    applied.add(block)
}
abstract class Config {
    abstract val eventBus: EventBus
    abstract operator fun<T: Any> get(clazz: KClass<T>): Datacontainer<T>?
    inline fun <reified T: Any> resolve(): Datacontainer<T>? = get(T::class)
    inline fun <reified T: Any> resolveOrThrow(): Datacontainer<T> = get(T::class) ?: throw Exception("Container not registered")

    inline infix fun<reified T: Event, reified TYPE: Any> EventThread<T>.bind(noinline action: EventBus.(TYPE, T) -> TYPE): EventThread<T> {
        invoke { event ->
            if (event is T) {
                with(eventBus) {
                    resolve<TYPE>()?.update { type: TYPE ->
                        action(type, event)
                    }
                }
            }
        }
        return this
    }

    inline fun<reified T: Event> eventThread(noinline action: EventBus.(Event) -> Unit) : EventThread<T> {

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
}
fun eventsBuilder(block: ScopeEventsThreadBuilder.() -> Unit): Config {
    val scope = ScopeEventsThreadBuilder().also(block)

    return scope.config
}