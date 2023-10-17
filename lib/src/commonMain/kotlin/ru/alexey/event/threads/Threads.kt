package ru.alexey.event.threads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.reflect.KClass

@OptIn(ExperimentalStdlibApi::class)
abstract class  EventThread<T>: AutoCloseable where T: Event {

    private val actionsMutable: MutableList<(Event) -> Unit> = mutableListOf()

    val actions: List<(Event) -> Unit>
        get() = actionsMutable

    operator fun invoke(block: (Event) -> Unit) {
        actionsMutable.add(block)
    }

}

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
                        return (builder.containers[clazz])?.let { it as Datacontainer<T> }
                    }
                }.also {
                    builder.channel = true
                }
        }
    }

    val configs = mutableMapOf<String, ConfigBuilder.() -> Unit>()

    /*fun threads(block: Config.() -> Unit) {
        with(config) {
            block()
        }
    }*/
}

class ConfigBuilder {
    var eventBus: EventBus = EventBus.defaultFactory()
    val containers: MutableMap<KClass<out Any>, Datacontainer<out Any>>
        = mutableMapOf()

    var channel = false
}

inline fun ScopeEventsThreadBuilder.config(noinline block: ConfigBuilder.() -> Unit) {
    configs.put("config", block)
}

inline fun ScopeEventsThreadBuilder.containers(noinline block: ConfigBuilder.() -> Unit) {
    configs.put("containers", block)
}

abstract class Config {
    abstract val eventBus: EventBus
    abstract operator fun<T: Any> get(clazz: KClass<T>): Datacontainer<T>?
    inline fun <reified T: Any> resolve(): Datacontainer<T>? = get(T::class)
    inline fun <reified T: Any> resolveOrThrow(): Datacontainer<T> = get(T::class) ?: throw Exception("${T::class.qualifiedName} not registered")

    inline infix fun<reified T: Event, reified TYPE: Any> EventThread<T>.bind(noinline action: EventBus.(TYPE, T) -> TYPE) {
        invoke { event ->
            if (event is T) {
                with(eventBus) {
                    resolve<TYPE>()?.update { type: TYPE ->
                        action(type, event)
                    }
                }
            }
        }
    }

    inline fun<reified T: Event> eventThread(noinline action: EventBus.(Event) -> Unit) : EventThread<T> {

        return object : EventThread<T>() {
            override fun close() {
                eventBus.unsubscribe<T>()
            }

            init {
                val eventBus = eventBus
                invoke {
                    with(eventBus) {
                        action(it)
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









