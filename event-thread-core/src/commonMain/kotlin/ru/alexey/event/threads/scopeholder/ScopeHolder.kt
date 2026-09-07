package ru.alexey.event.threads.scopeholder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.DummyProvider
import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass

internal class ExternalEventDefinition(
    val key: String,
    val event: Event
)

@OptIn(ExperimentalStdlibApi::class)
class ScopeHolder(
    val external: Map<KClass<out Event>, List<String>>,
    private val factories: Map<String, (Parameters, List<ScopeBuilder>) -> ScopeBuilder>,
    val dependencies: Map<String, List<String>> = emptyMap(),
    private val implementations: Map<String, List<String>> = emptyMap(),
    val dependencyProvider: DependencyProvider = DummyProvider()
) : AutoCloseable {

    private val active: MutableSet<Scope> = mutableSetOf()
    private val innerScope = CoroutineScope(Dispatchers.Default)
    private val externalEventsChannel = Channel<ExternalEventDefinition>()

    val activeMetadata
        get() = active.associate { it.key to it.metadata }

    init {
        innerScope.launch {
            for (eventDef in externalEventsChannel) {
                // Only the mappings whose registered event type actually matches this event -
                // the old version discarded the KClass key here and checked *every* mapping's
                // receivers regardless of type, so an event could leak to a scope that was only
                // ever configured to receive a *different* event type.
                val receivers = external.entries
                    .filter { (eventClass, _) -> eventClass.isInstance(eventDef.event) }
                    .flatMapTo(mutableSetOf()) { (_, receivers) -> receivers }
                if (receivers.isEmpty()) continue

                active.forEach { activeItem ->
                    // Exclude the scope that emitted this event - it already processed it once
                    // (that's how it ended up on .output). `deliverExternally` (rather than
                    // `+=`) delivers to the receiver's subscribers without re-emitting to *its*
                    // own .output - otherwise the receiver's re-emission would look like a new
                    // event eligible for another round of routing, bouncing forever between any
                    // two scopes that are both configured as receivers for this event.
                    if (activeItem.key != eventDef.key && activeItem.key in receivers) {
                        activeItem.eventBus.deliverExternally(eventDef.event)
                    }
                }
            }
        }
    }

    private fun getAllDeps(key: String, params: () -> Parameters): List<ScopeBuilder> {
        return implementations.getOrElse(key, ::emptyList).mapNotNull {
            factories[it]?.invoke(params(), getAllDeps(it, params))
        }
    }

    private fun loadInternal(key: String, params: () -> Parameters = ::emptyMap): Scope? {
        return factories[key]?.let {
            val scope = it(params(), getAllDeps(key, params))
            scope.build()
        }?.also { scope ->
            active += scope
            // Filtered here, per-scope, before anything reaches the shared channel: the vast
            // majority of events aren't external-routed at all, so this drops them in parallel
            // at the source instead of funneling every event from every scope through one
            // shared channel drained by a single coroutine.
            scope.eventBus.output
                .filter { event -> external.keys.any { it.isInstance(event) } }
                .map { event -> ExternalEventDefinition(scope.key, event) }
                .onEach { externalEventsChannel.send(it) }
                .launchIn(innerScope)
        }?.also {
            dependencies[it.key]?.forEach(::findOrLoad)
        }
    }


    @Deprecated("Use load(key) instead", ReplaceWith("load(key)"))
    infix fun load(keyHolder: KeyHolder): Scope? = load(keyHolder.key)

    infix fun load(key: String): Scope? {
        return loadInternal(key)
    }

    fun load(key: String, params: () -> Parameters): Scope? {
        return loadInternal(key, params)
    }

    infix fun free(keyHolder: KeyHolder) {
        free(keyHolder.key)
    }

    infix fun free(key: String) {
        val scope = active.find { it.key == key } ?: return

        val depsToFree = scope.dependencies

        val activeDeps = active.filter { it.key != key }.flatMap { it.dependencies }

        depsToFree.filter {
            it !in activeDeps
        }.forEach(::free)

        active.removeAll { it.key == key }
        scope.close()
    }

    private val Scope.dependencies: List<String>
        get() = this@ScopeHolder.dependencies[this.key] ?: emptyList()

    operator fun plus(event: Event) {

        val scopes = external.keys.filter { it.isInstance(event) }.flatMap {
            external[it] ?: emptyList()
        }.let {
            if (it.isEmpty()) {
                //broadcast case
                active
            } else {
                //external case
                active.filter { key -> key.key in it }
            }
        }

        for (scope in scopes) {
            scope + event
        }
    }

    infix fun find(key: String): Scope? = active.find { it.key == key }
    infix fun findOrLoad(key: String): Scope =
        find(key) ?: load(key) ?: error("Scope with name: $key not found")

    fun findOrLoad(key: String, params: () -> Parameters): Scope =
        find(key) ?: load(key, params) ?: error("Scope with name: $key not found")

    override fun close() {
        active.forEach { it.close() }
        active.clear()
        innerScope.cancel()
    }
}


