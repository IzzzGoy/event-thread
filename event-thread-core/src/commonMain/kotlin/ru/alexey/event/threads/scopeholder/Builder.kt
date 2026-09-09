package ru.alexey.event.threads.scopeholder

import ru.alexey.event.threads.Builder
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.DummyProvider
import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass

/**
 * Receiver for `scopeHolder { }` - declares every named scope a [ScopeHolder] will know about,
 * plus the `dependsOn`/`implements`/`consume` graphs between them, before [build] produces the
 * live [ScopeHolder]. Nothing declared here is built eagerly: [scopeEmbedded]/[scope] just
 * register a factory, actually invoked on first `load`/`findOrLoad`.
 */
class ScopeHolderBuilder {

    private val factories: MutableMap<String, (Parameters, List<ScopeBuilder>) -> ScopeBuilder> = mutableMapOf()
    private val external: MutableMap<KClass<out Event>, List<String>> = mutableMapOf()
    private val dependencies: MutableMap<String, List<String>> = mutableMapOf()
    private val implementations: MutableMap<String, List<String>> = mutableMapOf()
    private var dependencyProvider: DependencyProvider = DummyProvider()

    /** Sets the [DependencyProvider] every declared scope's [ScopeBuilder] gets by default. */
    @Builder
    fun dependencyProvider(provider: DependencyProvider) {
        this.dependencyProvider = provider
    }

    /**
     * Same registration as [scopeEmbedded], but returns [key] so it can be chained straight
     * into [dependsOn]/[implements] - lets a feature module export its scope setup as a plain
     * `ScopeBuilder.(Parameters) -> Unit` extension function (e.g. `ScopeBuilder::provideFooScope`)
     * that the composition root wires in without needing to know its internals.
     */
    fun scope(key: String, init: ScopeBuilder.(Parameters) -> Unit): String {
        scopeEmbedded(key, init)
        return key
    }

    /** Builds the [ScopeHolder]. */
    fun build(): ScopeHolder {
        return ScopeHolder(
            external = external,
            factories = factories,
            dependencies = dependencies,
            implementations = implementations,
            dependencyProvider = dependencyProvider
        )
    }

    /** Registers a scope factory under [key] - `init` runs against a fresh [ScopeBuilder] with
     * that key's resolved `implements` parents already applied, each time this scope is
     * actually loaded. */
    fun scopeEmbedded(key: String, init: ScopeBuilder.(Parameters) -> Unit) {
        factories[key] = { params, parents ->
            ScopeBuilder(key, parents, dependencyProvider).apply { init(params) }
        }
    }

    /** Declares that events of type [key] should be routed to [receivers] (by scope key) via
     * `ScopeHolder`'s external-routing loop, instead of only reaching whatever scope dispatched
     * them. See the `consume` infix overloads below for the usual DSL form. */
    fun external(key: KClass<out Event>, receivers: List<String>) {
        external[key] = receivers
    }

    /** `MyEvent::class consume "otherScope"` - routes every [this] event to [receiver] (if
     * active) in addition to wherever it was dispatched. */
    infix fun KClass<out Event>.consume(receiver: String) {
        external[this] = listOf(receiver)
    }

    /** [consume] with multiple receiver scope keys. */
    infix fun KClass<out Event>.consume(receivers: List<String>) {
        external[this] = receivers
    }

    /** [consume], with the receiver list computed lazily. */
    infix fun KClass<out Event>.consume(receivers: () -> List<String>) {
        external[this] = receivers()
    }

    /** `"child" dependsOn "parent"` - loading `"child"` also loads `"parent"`, and `"parent"` is
     * only freed once no other active scope still depends on it (see [ScopeHolder.free]). */
    infix fun String.dependsOn(key: String) {
        dependencies[this] = listOf(key)
    }

    /** [dependsOn] with multiple dependency keys. */
    infix fun String.dependsOn(key: List<String>) {
        dependencies[this] = key
    }

    /** [dependsOn], with the dependency list computed lazily. */
    infix fun String.dependsOn(keys: () -> List<String>) {
        dependencies[this] = keys()
    }

    /** `"child" implements "base"` - `"child"`'s [ScopeBuilder] inherits `"base"`'s config/
     * threads/containers/emitters (see [ScopeBuilder.apply]) before its own `init` block runs. */
    infix fun String.implements(key: String) {
        implementations[this] = listOf(key)
    }

    /** [implements] with multiple parent keys - a diamond-shaped graph (two parents sharing a
     * common ancestor) still applies that ancestor's registrations exactly once. */
    infix fun String.implements(key: List<String>) {
        implementations[this] = key
    }

    /** [implements], with the parent list computed lazily. */
    infix fun String.implements(keys: () -> List<String>) {
        implementations[this] = keys()
    }
}


/** Builds a [ScopeHolder] by applying [block] to a fresh [ScopeHolderBuilder]. */
fun scopeHolder(block: ScopeHolderBuilder.() -> Unit): ScopeHolder {
    return ScopeHolderBuilder().apply(block).build()
}