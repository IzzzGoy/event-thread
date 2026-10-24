package ru.alexey.event.threads.scopeholder

import ru.alexey.event.threads.Builder
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.DummyProvider
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.scopeholder.typed.ScopeKey
import ru.alexey.event.threads.scopeholder.typed.ScopeTyped
import ru.alexey.event.threads.scopeholder.typed.resolveTyped
import kotlin.reflect.KClass

/**
 * Receiver for `scopeHolder { }` - declares every named scope a [ScopeHolder] will know about,
 * plus the `dependsOn`/`implements`/`consume` graphs between them, before [build] produces the
 * live [ScopeHolder]. Nothing declared here is built eagerly: [scopeEmbedded]/[scope] just
 * register a factory, actually invoked on first `load`/`findOrLoad`.
 *
 * [scopeEmbedded]/[scope]/`dependsOn`/`implements`/`consume` all have a [ScopeKey]-keyed overload
 * alongside the original `String`-keyed one - same immediate, single-block registration, just
 * typed. Reach for `ScopeKey.by`/`ScopeKey.extend`/`Scope.of` + `include(...)`
 * (`scopeholder.typed` package) instead only when a scope's declaration or content actually needs
 * composing from more than one call site/module.
 */
class ScopeHolderBuilder {

    private val factories: MutableMap<String, (Parameters, List<ScopeBuilder>) -> ScopeBuilder> = mutableMapOf()
    private val scopeFactories: MutableMap<String, (Parameters, DependencyProvider) -> Scope> = mutableMapOf()
    private val external: MutableMap<KClass<out Event>, List<String>> = mutableMapOf()
    private val dependencies: MutableMap<String, List<String>> = mutableMapOf()
    private val implementations: MutableMap<String, List<String>> = mutableMapOf()
    private var dependencyProvider: DependencyProvider = DummyProvider()

    // Lets an external DSL layer (see `ru.alexey.event.threads.scopeholder.typed.include`) defer
    // its own registration until [build] is actually reached, instead of committing eagerly per
    // call - needed because a scope's typed config can arrive in pieces across several calls (one
    // module's own declaration, another module's `extend`), and this builder's own
    // dependsOn/implements/consume/scopeEmbedded all *replace* rather than merge on repeat calls
    // for the same key. Deliberately untyped (`ScopeHolderBuilder.() -> Unit`, no dependency on any
    // specific external DSL's types) so this stays a generic hook, not a typed-layer-specific one.
    private val onBuildActions = mutableListOf<ScopeHolderBuilder.() -> Unit>()

    // Read access for `include` (typed/Ext.kt), which must merge its fragments' relations into
    // what's already declared here instead of replacing it.
    internal fun dependenciesOf(key: String): List<String> = dependencies[key].orEmpty()
    internal fun implementationsOf(key: String): List<String> = implementations[key].orEmpty()
    internal fun receiversOf(event: KClass<out Event>): List<String> = external[event].orEmpty()

    /** Registers [action] to run against this builder just before [build] assembles the final
     * [ScopeHolder] - runs in registration order, after everything else declared in this block. */
    fun onBuild(action: ScopeHolderBuilder.() -> Unit) {
        onBuildActions += action
    }

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
        onBuildActions.forEach { it() }
        // ScopeHolder resolves `implements` by building parents' ScopeBuilders and silently skips
        // a parent it has no ScopeBuilder factory for - a typed scope has none, and a typed child
        // has no ScopeBuilder to receive the inherited content. Either way the declared
        // inheritance would just not happen, so refuse it here.
        val typedInImplements = implementations.filter { (child, parents) ->
            child in scopeFactories || parents.any { it in scopeFactories }
        }
        check(typedInImplements.isEmpty()) {
            "Typed scopes can't take part in `implements` (no ScopeBuilder content to inherit or receive): $typedInImplements"
        }
        return ScopeHolder(
            external = external,
            factories = factories,
            dependencies = dependencies,
            implementations = implementations,
            dependencyProvider = dependencyProvider,
            scopeFactories = scopeFactories,
        )
    }

    /** Registers a scope factory under [key] - `init` runs against a fresh [ScopeBuilder] with
     * that key's resolved `implements` parents already applied, each time this scope is
     * actually loaded. */
    fun scopeEmbedded(key: String, init: ScopeBuilder.(Parameters) -> Unit) {
        check(key !in scopeFactories) { "Scope '$key' is already registered as a typed scope" }
        factories[key] = { params, parents ->
            ScopeBuilder(key, parents, dependencyProvider).apply { init(params) }
        }
    }

    /** [scopeEmbedded], keyed by a typed [ScopeKey] instead of a raw `String` - the same
     * single-block, declare-and-build-right-here style the `String` overload has always had, just
     * typed: registers immediately, right here, no `ScopeKey.by`/`Scope.of` + `include(...)`
     * indirection required. Reach for that pair instead only once this scope's declaration or
     * content actually needs composing from more than one place (see `typed/Ext.kt`'s KDoc) -
     * a plain single-owner scope never needs it. */
    @Suppress("UNCHECKED_CAST")
    fun <P : Any> scopeEmbedded(key: ScopeKey<P>, init: ScopeBuilder.(P) -> Unit) {
        scopeEmbedded(key.identity) { parameters -> init(parameters.resolveTyped() as P) }
    }

    /**
     * Registers a typed scope (a [ScopeTyped], e.g. a generated `TodoScope`) under [key]:
     * `scopeTyped(TodoScopeKey, ::TodoScope)`. [factory] builds a fresh instance on every load,
     * from the params given to `ScopeHolder.load(key, params)` and this holder's
     * [DependencyProvider] (read at load time, so [dependencyProvider] may be set after this call).
     *
     * Loading, freeing, `dependsOn` and `consume` routing work as for any other scope; `implements`
     * doesn't apply (rejected at [build]). Each distinct params value is its own instance
     * (`load(TodoScopeKey, TodoListParams("Work"))` and `("Personal")` live side by side) - see
     * [ScopeHolder.find]/[ScopeHolder.free] for picking one.
     */
    inline fun <reified P : Any> scopeTyped(
        key: ScopeKey<P>,
        noinline factory: (P, DependencyProvider) -> ScopeTyped<P>,
    ) = registerTyped(key, P::class, factory)

    /** [scopeTyped] for a `ScopeKey<Unit>` - `scopeTyped(TodoDomainScopeKey, ::TodoDomainScope)`. */
    fun scopeTyped(key: ScopeKey<Unit>, factory: (DependencyProvider) -> ScopeTyped<Unit>) =
        registerTyped(key, Unit::class) { _, provider -> factory(provider) }

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun <P : Any> registerTyped(
        key: ScopeKey<P>,
        paramsType: KClass<P>,
        factory: (P, DependencyProvider) -> ScopeTyped<P>,
    ) {
        val identity = key.identity
        check(identity !in factories && identity !in scopeFactories) { "Scope '$identity' is already registered" }
        scopeFactories[identity] = { parameters, provider ->
            val params = parameters.resolveTyped()
            // `dependsOn` loads by key alone, so a scope needing params can arrive with none -
            // say so instead of letting a Unit reach the constructor as a ClassCastException.
            require(paramsType.isInstance(params)) {
                "Scope '$identity' needs ${paramsType.simpleName} params, got ${params::class.simpleName} - " +
                    "load it with ScopeHolder.load(key, params) (dependsOn can't supply params)"
            }
            factory(params as P, provider).also { scope ->
                // ScopeHolder finds/frees/routes by `scope.key`: a scope under another key could
                // never be found again.
                check(scope.key == identity) { "Factory for '$identity' built a scope keyed '${scope.key}'" }
            }
        }
    }

    /** [scope], keyed by a typed [ScopeKey] instead of a raw `String`. */
    fun <P : Any> scope(key: ScopeKey<P>, init: ScopeBuilder.(P) -> Unit): ScopeKey<P> {
        scopeEmbedded(key, init)
        return key
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

    /** [consume], keyed by a typed [ScopeKey] instead of a raw `String`. */
    infix fun KClass<out Event>.consume(receiver: ScopeKey<*>) {
        external[this] = listOf(receiver.identity)
    }

    /** [consume] with multiple receiver scope keys - `Collection`, not `List`, purely so this
     * overload's erased parameter type doesn't clash on the JVM with the `String` one's `List`. */
    infix fun KClass<out Event>.consume(receivers: Collection<ScopeKey<*>>) {
        external[this] = receivers.map { it.identity }
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

    /** [dependsOn], keyed by a typed [ScopeKey] instead of a raw `String`. */
    infix fun ScopeKey<*>.dependsOn(key: ScopeKey<*>) {
        dependencies[identity] = listOf(key.identity)
    }

    /** [dependsOn] with multiple dependency keys. */
    infix fun ScopeKey<*>.dependsOn(keys: List<ScopeKey<*>>) {
        dependencies[identity] = keys.map { it.identity }
    }

    /** [dependsOn], with the dependency list computed lazily. */
    infix fun ScopeKey<*>.dependsOn(keys: () -> List<ScopeKey<*>>) {
        dependencies[identity] = keys().map { it.identity }
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

    /** [implements], keyed by a typed [ScopeKey] instead of a raw `String`. */
    infix fun ScopeKey<*>.implements(key: ScopeKey<*>) {
        implementations[identity] = listOf(key.identity)
    }

    /** [implements] with multiple parent keys. */
    infix fun ScopeKey<*>.implements(keys: List<ScopeKey<*>>) {
        implementations[identity] = keys.map { it.identity }
    }

    /** [implements], with the parent list computed lazily. */
    infix fun ScopeKey<*>.implements(keys: () -> List<ScopeKey<*>>) {
        implementations[identity] = keys().map { it.identity }
    }
}


/** Builds a [ScopeHolder] by applying [block] to a fresh [ScopeHolderBuilder]. */
fun scopeHolder(block: ScopeHolderBuilder.() -> Unit): ScopeHolder {
    return ScopeHolderBuilder().apply(block).build()
}