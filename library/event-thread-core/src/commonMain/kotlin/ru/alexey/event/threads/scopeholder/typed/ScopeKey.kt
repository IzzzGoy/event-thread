package ru.alexey.event.threads.scopeholder.typed

import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.bus.Event
import kotlin.reflect.KClass

/**
 * Typed identity for a scope, replacing a hand-typed `String` key. [P] is that scope's load-time
 * parameters - no more casting out of the untyped `Parameters` bag. Declare one
 * `object FooScope : ScopeKey<FooScope.Params>` per scope (or `ScopeKey<Unit>` for one that needs
 * none) and pass that object itself wherever a scope needs naming (`dependsOn`/`implements`/
 * `consume`/`load`/`find`/`free`) - never a string.
 *
 * Every [ScopeKey] must be a singleton `object`, never a per-instance class - dynamism (a scope per
 * list item, say) belongs in [P], not in the key; see [Scope.of]. This also keeps
 * [identity] cheap and portable: every consumer already holds the actual instance (there's only
 * ever one), so nothing here needs `KClass` reflection to go from "a type" to "the identity of
 * that type" - two ways that turned out to matter in practice while prototyping this: recovering a
 * singleton from a bare `KClass` (`KClass.objectInstance`) requires the full `kotlin-reflect`
 * runtime on the JVM target, and `KClass.qualifiedName` doesn't compile at all on Kotlin/JS.
 */
interface ScopeKey<P : Any> {
    /** Defaults to the simple class name. Override to disambiguate two unrelated scopes that
     * happen to share a simple name in different packages/modules (there is no portable
     * `KClass.qualifiedName` to fall back on - see the class KDoc), or to give a scope a specific
     * name for [ScopeKeyBuilder.legacyAlias]-free interop with an old string-keyed caller. */
    val identity: String
        get() = this::class.simpleName
            ?: error("Anonymous ScopeKey ($this) has no stable identity - override `identity`")

    /** Anchor for the `by`/`extend`/`from` factory functions in `Ext.kt` - see each. */
    companion object
}

/**
 * Something [ru.alexey.event.threads.scopeholder.typed.include] can fold into a [ScopeHolderBuilder]
 * registration for [identity] - either a [ScopeKeyFragment] (topology: does this identity get
 * declared at all, and its `dependsOn`/`implements`/`consume`/`legacyAlias` edges) or a
 * [ScopeKeyBody] (this scope's actual `ScopeBuilder` content). Kept as two separate types rather
 * than fields on one builder so that *declaring/wiring* a scope's place in the graph and
 * *constructing* its content are never entangled in a single call - see [Scope.of]'s KDoc for
 * why that split exists.
 */
sealed interface ScopeKeyContribution {
    val identity: String
}

/**
 * One topology contribution towards a scope's final configuration, identified by [identity].
 * Multiple fragments sharing the same [identity] - however many modules created them, wherever
 * they were declared - are unioned/sequenced once handed to
 * [ru.alexey.event.threads.scopeholder.typed.include] (see that function's KDoc): the same
 * multi-binding-by-key shape already used by `Datacontainer`. This is what lets a module
 * [ScopeKey.extend] a scope it doesn't own without touching that scope's own declaration.
 *
 * Carries no `ScopeBuilder` content of its own - see [ScopeKeyBody] for that. Pure data - building
 * one has no side effect on any holder. It only does anything once handed to `include`,
 * deliberately: no ambient/global registry, so nothing leaks between independently built holders
 * (e.g. across tests).
 */
class ScopeKeyFragment<P : Any> internal constructor(
    override val identity: String,
    internal val declaresScope: Boolean,
    internal val dependsOn: Set<String>,
    internal val implementsSet: Set<String>,
    internal val consumes: Set<KClass<out Event>>,
    internal val legacyAliases: Set<String>,
) : ScopeKeyContribution

/**
 * A scope's actual container/thread/emitter setup, built from [Scope.of] - kept entirely
 * separate from [ScopeKeyFragment]/[ScopeKeyBuilder] so that declaring a scope's place in the
 * topology (owned by [ScopeKey.by]/[ScopeKey.extend], never touching a `ScopeBuilder`) can't be
 * confused with, or accidentally gain access to, constructing what's actually inside it. Whether
 * the target identity is *declared at all* is still decided purely by [ScopeKeyFragment] -
 * contributing a body for an identity nothing ever declares does nothing (mirrors an [ScopeKey.extend]-
 * only group having nowhere to register, see `applyTypedFragments` in `Ext.kt`).
 */
class ScopeKeyBody<P : Any> internal constructor(
    override val identity: String,
    internal val block: ScopeBuilder.(Any) -> Unit,
) : ScopeKeyContribution

/** DSL receiver for [ScopeKey.by]/[ScopeKey.extend]/[ScopeKey.from]'s trailing block - topology
 * only (`dependsOn`/`implements`/`consume`/`legacyAlias`). Never sees a `ScopeBuilder`: a scope's
 * actual content is declared separately via [Scope.of], so nothing here can reach into, or be
 * mistaken for, that construction step.
 *
 * [declaresScope] distinguishes [ScopeKey.by]/[ScopeKey.from] (this fragment is the/a owning
 * declaration - the identity's group must get a real `scopeEmbedded` registration even with zero
 * [ScopeKeyBody] contributions) from [ScopeKey.extend] (this fragment only contributes relations to
 * a scope declared - and `scopeEmbedded`-registered - elsewhere, so it must never cause a blank
 * factory to be registered that would shadow the real one). */
class ScopeKeyBuilder<P : Any> internal constructor(
    private val identity: String,
    internal val declaresScope: Boolean,
) {
    private val dependsOnSet = mutableSetOf<String>()
    private val implementsSet = mutableSetOf<String>()
    private val consumesSet = mutableSetOf<KClass<out Event>>()
    private val legacyAliasSet = mutableSetOf<String>()

    /** `dependsOn(AuthScope)` - loading this scope also loads [keys]. */
    fun dependsOn(vararg keys: ScopeKey<*>) {
        dependsOnSet += keys.map { it.identity }
    }

    /** `implements(BaseScope)` - this scope's [ScopeBuilder] inherits `BaseScope`'s config/
     * threads/containers/emitters before this scope's own content (see [Scope.of]) runs. */
    fun implements(vararg keys: ScopeKey<*>) {
        implementsSet += keys.map { it.identity }
    }

    /** `consume<SomeEvent>()` - routes `SomeEvent` to this scope in addition to wherever it was
     * dispatched. Event types (unlike [ScopeKey]s) are matched by [KClass.isInstance] elsewhere in
     * this library already, so reified `KClass` lookup here is the existing, portable idiom - not
     * the identity-from-bare-type problem [ScopeKey]'s KDoc describes. */
    inline fun <reified E : Event> consume() = consume(E::class)
    fun consume(vararg events: KClass<out Event>) {
        consumesSet += events
    }

    /** Lets the deprecated `ScopeHolder.load(key: String)` (and `find`/`free`) reach this scope by
     * an old, pre-migration name, without that name having any bearing on the canonical
     * [ScopeKey.identity] used everywhere else. Purely additive - safe to call from any fragment
     * contributing to this scope, not just the one that first declared it.
     *
     * Known limitation: under the hood this registers a second, independent factory under [names]
     * (see `applyTypedFragments` in `Ext.kt`) - loading via the alias produces its own
     * `Scope` instance rather than sharing the one loaded via the canonical identity. Fine for
     * "old code migrating off a string, not touching the scope concurrently from both names" - not
     * a general two-names-one-instance mechanism. */
    fun legacyAlias(vararg names: String) {
        legacyAliasSet += names
    }

    internal fun build(): ScopeKeyFragment<P> = ScopeKeyFragment(
        identity = identity,
        declaresScope = declaresScope,
        dependsOn = dependsOnSet,
        implementsSet = implementsSet,
        consumes = consumesSet,
        legacyAliases = legacyAliasSet,
    )
}
