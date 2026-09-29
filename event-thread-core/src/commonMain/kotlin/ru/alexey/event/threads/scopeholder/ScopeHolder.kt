package ru.alexey.event.threads.scopeholder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
import ru.alexey.event.threads.utils.removeAndGet
import ru.alexey.event.threads.utils.update
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

internal class ExternalEventDefinition(
    val key: String,
    val event: Event
)

/**
 * Owns the lifecycle of every named [Scope] in the app: builds/frees them on demand, resolves
 * `implements`/`dependsOn` graphs, and routes events declared `consume`-able between scopes.
 *
 * **Thread-safety:** every public method here (`load`/`free`/`find`/`findOrLoad`/`plus`/`close`)
 * is safe to call concurrently from any thread. The active-scope and routing-job registries are
 * lock-free copy-on-write snapshots (see [ru.alexey.event.threads.utils.update]) specifically so
 * that a UI-thread `findOrLoad`/`free` call and the background external-routing loop below can
 * race freely without corrupting shared state. This only covers `ScopeHolder`'s own bookkeeping,
 * not what a resolved [Scope]'s containers/threads do internally - see [ru.alexey.event.threads.bus.EventBus]'s
 * own thread-safety note for that.
 */
@OptIn(ExperimentalStdlibApi::class, ExperimentalAtomicApi::class)
class ScopeHolder(
    val external: Map<KClass<out Event>, List<String>>,
    private val factories: Map<String, (Parameters, List<ScopeBuilder>) -> ScopeBuilder>,
    val dependencies: Map<String, List<String>> = emptyMap(),
    private val implementations: Map<String, List<String>> = emptyMap(),
    val dependencyProvider: DependencyProvider = DummyProvider()
) : AutoCloseable {

    // `active`/`routingJobs` are read from the external-routing loop below on `Dispatchers.
    // Default` while `loadInternal`/`free` are called from whatever thread the caller uses
    // (typically Compose's main thread) - a plain MutableSet/MutableMap mutated on one thread
    // while iterated on another is a real, reachable data race (any app that both `consume`-routes
    // and loads/frees scopes while routing is live), not just a theoretical one. Copy-on-write
    // over an atomic reference means every read sees a consistent, unmodifiable snapshot with no
    // locking needed on the (much hotter) read side.
    private val activeRef = AtomicReference<Set<Scope>>(emptySet())
    private val active: Set<Scope> get() = activeRef.load()
    private val innerScope = CoroutineScope(Dispatchers.Default)
    private val externalEventsChannel = Channel<ExternalEventDefinition>()

    // The routing collector launched per scope in loadInternal() below never completes on its
    // own - it collects a SharedFlow, which by contract never completes - so without tracking
    // its Job here and cancelling it in free(), every load/free cycle (e.g. switching tabs) added
    // one more collector that lived for the rest of the process, each still holding a reference
    // to its now-closed Scope.
    private val routingJobsRef = AtomicReference<Map<String, Job>>(emptyMap())

    val activeMetadata
        get() = active.associate { it.key to it.metadata }

    init {
        innerScope.launch {
            for (eventDef in externalEventsChannel) {
                // This coroutine is the sole reader of `externalEventsChannel` (a rendezvous
                // channel, capacity 0): if its body throws uncaught, the loop dies permanently -
                // every later `externalEventsChannel.send()` from a per-scope routing collector
                // (see loadInternal) then suspends forever with no receiver left, and `consume`
                // routing silently stops working for the rest of the process. A receiver's own
                // scope can be `free()`'d concurrently with delivery reaching it, which throws a
                // CancellationException belonging to *that* (now-closed) scope's job, not to this
                // loop's own job - only rethrow when this coroutine's own job is the one actually
                // being cancelled; otherwise report and keep routing later events, the same
                // "one failure shouldn't kill the whole dispatcher" contract EventBus enforces
                // for its own subscribers.
                try {
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
                } catch (c: CancellationException) {
                    if (!isActive) throw c
                } catch (t: Throwable) {
                    println("ScopeHolder: unhandled exception while routing $eventDef: $t")
                }
            }
        }
    }

    // Collects the full set of ancestor keys first and builds each one exactly once, rather than
    // recursing edge-by-edge: a diamond-shaped `implements` graph (e.g. "Y" implements ["Base",
    // "X"], "X" implements "Base") used to build one independent ScopeBuilder per *edge* - Base
    // got built once directly for Y and again nested inside X's own build, so ScopeBuilder.apply
    // copied Base's config/threads/emitters into Y twice. Every ancestor here is built with
    // `parents = emptyList()` since the flattened `visited` set already includes its own
    // ancestors, so each contributes only its own directly-declared content, exactly once,
    // regardless of how many paths reach it.
    private fun getAllDeps(key: String, params: () -> Parameters): List<ScopeBuilder> {
        val visited = LinkedHashSet<String>()
        fun collect(k: String) {
            implementations.getOrElse(k, ::emptyList).forEach { parentKey ->
                if (visited.add(parentKey)) collect(parentKey)
            }
        }
        collect(key)
        return visited.mapNotNull { factories[it]?.invoke(params(), emptyList()) }
    }

    private fun loadInternal(key: String, params: () -> Parameters = ::emptyMap): Scope? {
        return factories[key]?.let {
            val scope = it(params(), getAllDeps(key, params))
            scope.build()
        }?.also { scope ->
            activeRef.update { it + scope }
            // Filtered here, per-scope, before anything reaches the shared channel: the vast
            // majority of events aren't external-routed at all, so this drops them in parallel
            // at the source instead of funneling every event from every scope through one
            // shared channel drained by a single coroutine.
            val routingJob = scope.eventBus.output
                .filter { event -> external.keys.any { it.isInstance(event) } }
                .map { event -> ExternalEventDefinition(scope.key, event) }
                .onEach { externalEventsChannel.send(it) }
                .launchIn(innerScope)
            routingJobsRef.update { it + (scope.key to routingJob) }
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

        activeRef.update { current -> current.filterNot { it.key == key }.toSet() }
        routingJobsRef.removeAndGet(key)?.cancel()
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
        activeRef.store(emptySet())
        innerScope.cancel()
    }
}


