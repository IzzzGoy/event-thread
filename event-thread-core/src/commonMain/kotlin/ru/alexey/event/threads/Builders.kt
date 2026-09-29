package ru.alexey.event.threads

import ru.alexey.event.threads.EventBus.Companion.defaultFactory
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.scopeholder.KeyHolder
import ru.alexey.event.threads.datacontainer.ContainerBuilder
import ru.alexey.event.threads.datacontainer.DatacontainerKey
import ru.alexey.event.threads.emitter.Emitter
import ru.alexey.event.threads.emitter.EmittersBuilder
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.scopeholder.ScopeHolder
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

class ScopeBuilder(
    private var name: String,
    private var parents: List<ScopeBuilder> = emptyList()
) {
    private var configs: ConfigBuilder.() -> Unit = {}
    val containerBuilder = ContainerBuilder()
    private val applied = mutableListOf<Scope.() -> Unit>()
    private val emittersBuilder = EmittersBuilder()
    init {
        parents.forEach(::apply)
    }
    val scope: Scope
            by lazy {
                with(ConfigBuilder()){
                    configs()

                    object : Scope() {

                        val config = this@with.build()

                        override val key: String = name
                        override val eventBus: EventBus = config.eventBus
                        override val description: String = config.description
                        override fun <T : Any> get(clazz: KClass<T>): Datacontainer<T>? = containerBuilder[clazz]

                        init {
                            applied.forEach {
                                it()
                            }
                            emitters = emittersBuilder.build(this)
                        }
                    }
                }
            }



    @Builder
    fun config(block: ConfigBuilder.() -> Unit) {
        configs = block
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
    }
}

class ConfigBuilder {
    private var eventBus: EventBus = defaultFactory()
    private var description: String = ""
    @Builder
    fun createEventBus(block: EventBussBuilder.() -> Unit) {
        eventBus = with(EventBussBuilder().also(block)) { build() }
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

abstract class Scope : KeyHolder {

    abstract val eventBus: EventBus
    abstract val description: String
    val metadata
        get() = ScopeMetadata(description, eventBus.metadata)
    protected lateinit var emitters: List<Emitter<out Event>>
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

    operator fun plus(event: Event) = eventBus + event


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
            eventBus + factory(it)
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
            val new = factory(datacontainer.value, it)
            datacontainer.update {
                new
            }
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
    noinline block: ScopeBuilder.(Parameters) -> Unit
): (Parameters) -> ScopeBuilder =
    scopeBuilder(keyHolder?.key, parents, block)

@Builder
fun scopeBuilder(
    name: String? = null,
    parents: List<ScopeBuilder> = emptyList(),
    block: ScopeBuilder.(Parameters) -> Unit
): (Parameters) -> ScopeBuilder {
    return {
        ScopeBuilder(
            name ?: Random.nextBytes(132).toString(),
            parents
        ).apply { block(it) }
    }
}

@DslMarker
annotation class Builder


interface ExternalEventWrapper<T : Event> {
    fun handle(event: T)
    val key: KClass<T>
}

fun <T : Event> Scope.handle(event: ExternalEventWrapper<T>) {
    eventBus.external(event.key) {
        if (event.key.isInstance(it)) {
            event.handle(it as T)
        }
    }
}

