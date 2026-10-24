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
import ru.alexey.event.threads.scopeholder.typed.resolveTyped
import ru.alexey.event.threads.utils.removeAndGet
import ru.alexey.event.threads.utils.update
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

/** A live scope instance: its [key], the typed [params] it was loaded with (`Unit` for an
 * untyped/DSL load), and its [metadata]. See [ScopeHolder.activeInstances]. */
data class ActiveScopeInfo(
    val key: String,
    val params: Any,
    val metadata: ru.alexey.event.threads.ScopeMetadata,
)

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
    val dependencyProvider: DependencyProvider = DummyProvider(),
    // Scopes built ready-made rather than from a ScopeBuilder - typed scopes (see
    // ScopeHolderBuilder.scopeTyped). Handed this holder's dependencyProvider at load time.
    private val scopeFactories: Map<String, (Parameters, DependencyProvider) -> Scope> = emptyMap(),
) : AutoCloseable {

    // `active`/`routingJobs` are read from the external-routing loop below on `Dispatchers.
    // Default` while `loadInternal`/`free` are called from whatever thread the caller uses
    // (typically Compose's main thread) - a plain MutableSet/MutableMap mutated on one thread
    // while iterated on another is a real, reachable data race (any app that both `consume`-routes
    // and loads/frees scopes while routing is live), not just a theoretical one. Copy-on-write
    // over an atomic reference means every read sees a consistent, unmodifiable snapshot with no
    // locking needed on the (much hotter) read side.
    // One entry per live scope *instance*: a key loaded with different typed params (a scope per
    // list, per item) is several instances; `instance` is those params (`Unit` for an untyped/DSL
    // load - so a DSL scope still has exactly one instance per key, as always).
    private class ActiveScope(val scope: Scope, val instance: Any)

    private val entriesRef = AtomicReference<List<ActiveScope>>(emptyList())
    private val entries: List<ActiveScope> get() = entriesRef.load()
    private val active: Set<Scope> get() = entries.mapTo(LinkedHashSet()) { it.scope }
    private val innerScope = CoroutineScope(Dispatchers.Default)
    private val externalEventsChannel = Channel<ExternalEventDefinition>()

    // The routing collector launched per scope in loadInternal() below never completes on its
    // own - it collects a SharedFlow, which by contract never completes - so without tracking
    // its Job here and cancelling it in free(), every load/free cycle (e.g. switching tabs) added
    // one more collector that lived for the rest of the process, each still holding a reference
    // to its now-closed Scope.
    private val routingJobsRef = AtomicReference<Map<Scope, Job>>(emptyMap())

    /** One entry per live scope instance - a key loaded with several params values appears once
     * per instance. */
    val activeInstances: List<ActiveScopeInfo>
        get() = entries.map { ActiveScopeInfo(it.scope.key, it.instance, it.scope.metadata) }

    @Deprecated(
        "One entry per key: with several live instances of a key (typed params) only one shows. Use activeInstances.",
        ReplaceWith("activeInstances"),
    )
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

    private fun entry(key: String, instance: Any): ActiveScope? =
        entries.find { it.scope.key == key && it.instance == instance }

    private fun loadInternal(key: String, params: () -> Parameters = ::emptyMap): Scope? {
        val parameters = params()
        val instance = parameters.resolveTyped()
        // Already live: hand back that instance. Building a second one under the same identity
        // used to put two scopes with one key into the active set - `find` then returned an
        // arbitrary one, and `free` dropped both but closed only one, leaking the other's bus and
        // routing collector.
        entry(key, instance)?.let { return it.scope }

        val built = scopeFactories[key]?.let { factory ->
            // Touching eventBus wires a typed scope (containers, handlers from the provider) now,
            // before it's registered as active below: a missing binding then fails this load
            // cleanly instead of leaving a half-built scope in the active set.
            factory(parameters, dependencyProvider).also { it.eventBus }
        } ?: factories[key]?.let {
            it(parameters, getAllDeps(key) { parameters }).build()
        } ?: return null

        // Check-and-add in one CAS: two threads loading the same instance at once must end up
        // with one scope, not two - the loser closes its copy and returns the winner's.
        while (true) {
            val current = entriesRef.load()
            current.find { it.scope.key == key && it.instance == instance }?.let { winner ->
                built.close()
                return winner.scope
            }
            if (entriesRef.compareAndSet(current, current + ActiveScope(built, instance))) break
        }
        // Filtered here, per-scope, before anything reaches the shared channel: the vast
        // majority of events aren't external-routed at all, so this drops them in parallel
        // at the source instead of funneling every event from every scope through one
        // shared channel drained by a single coroutine.
        val routingJob = built.eventBus.output
            .filter { event -> external.keys.any { it.isInstance(event) } }
            .map { event -> ExternalEventDefinition(built.key, event) }
            .onEach { externalEventsChannel.send(it) }
            .launchIn(innerScope)
        routingJobsRef.update { it + (built to routingJob) }
        dependencies[key]?.forEach(::findOrLoad)
        return built
    }


    @Deprecated("Use load(key) instead", ReplaceWith("load(key)"))
    infix fun load(keyHolder: KeyHolder): Scope? = load(keyHolder.key)

    /** Loads the (untyped) [key] - or returns it, if that instance is already live. */
    infix fun load(key: String): Scope? {
        return loadInternal(key)
    }

    /** Loads [key] with [params] - or returns it, if that instance is already live. Typed params
     * (see `typedParameters`) make it a distinct instance per params value. */
    fun load(key: String, params: () -> Parameters): Scope? {
        return loadInternal(key, params)
    }

    infix fun free(keyHolder: KeyHolder) {
        free(keyHolder.key)
    }

    /** Frees every live instance of [key]. */
    infix fun free(key: String) {
        entries.filter { it.scope.key == key }.forEach { free(it.scope) }
    }

    /**
     * Frees exactly this [scope] instance, then each of its key's dependencies no *other* live
     * instance still depends on - another instance of the same key counts: freeing one todo list
     * must not free what the other lists still need.
     */
    infix fun free(scope: Scope) {
        val entry = entries.find { it.scope === scope } ?: return
        val stillNeeded = entries.filter { it !== entry }.flatMap { it.scope.dependencies }.toSet()

        scope.dependencies.filter { it !in stillNeeded }.forEach { free(it) }

        entriesRef.update { current -> current.filterNot { it.scope === scope } }
        routingJobsRef.removeAndGet(scope)?.cancel()
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

    /**
     * The live instance of [key]: its only one, or - with several (typed params) - the untyped
     * one. Throws when several are live and none is untyped: which one is meant can't be told
     * from the key alone, use [find] with params.
     */
    infix fun find(key: String): Scope? {
        val instances = entries.filter { it.scope.key == key }
        return when (instances.size) {
            0 -> null
            1 -> instances.single().scope
            else -> instances.find { it.instance == Unit }?.scope
                ?: error("Scope '$key' has ${instances.size} live instances - find it by its params")
        }
    }

    /** The live instance of [key] loaded with these typed [params], if any. */
    fun find(key: String, params: Any): Scope? = entry(key, params)?.scope

    infix fun findOrLoad(key: String): Scope =
        find(key) ?: load(key) ?: error("Scope with name: $key not found")

    /** The live instance of [key] for [params] - matched by its typed params, see [load] - or a
     * freshly loaded one. */
    fun findOrLoad(key: String, params: () -> Parameters): Scope {
        val parameters = params()
        val instance = parameters.resolveTyped()
        // Untyped params can't name an instance - mean "this scope", as before instances existed:
        // e.g. a widget's `scope("Work")` must reach the Work list the tab loaded with typed params.
        val live = if (instance == Unit) find(key) else entry(key, instance)?.scope
        return live
            ?: load(key) { parameters }
            ?: error("Scope with name: $key not found")
    }

    override fun close() {
        active.forEach { it.close() }
        entriesRef.store(emptyList())
        innerScope.cancel()
    }
}


