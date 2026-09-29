package ru.alexey.event.threads.scopeholder.typed

import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.ScopeHolderBuilder

// ---------------------------------------------------------------------------------------------
// Every extension function of the typed ScopeKey DSL (ScopeKey.kt's types), grouped in one place
// so this whole surface - factories, the load/find/free bridge to ScopeHolder, and the deferred
// `include` composition - can be found and read together instead of split across files.
//
// The `include`/`applyTypedFragments` machinery bridges onto the existing, production-hardened
// ScopeHolderBuilder/ScopeHolder untouched - every fragment ultimately becomes a plain
// `scopeEmbedded(identity, ...)`/`identity dependsOn ...`/`identity implements ...`/
// `event consume identity` call on the real builder, so all of ScopeHolder's own concurrency,
// diamond-`implements` flattening and external-event routing keeps working exactly as before.
// The generic `onBuild` hook (see Builder.kt) is what lets registration be deferred until every
// `include(...)` call across a block has been collected - this file is the only thing that knows
// what a ScopeKey fragment/body actually is.
//
// That deferred, multi-contribution machinery is for the case a single `scopeEmbedded` call can't
// serve: a scope declared in one module and topology-extended/content-layered from another. A
// scope with exactly one owner and no such composition doesn't need any of it - see the typed
// `scopeEmbedded`/`dependsOn`/`implements`/`consume` overloads declared directly on
// ScopeHolderBuilder itself (Builder.kt), which register immediately, the same way the original
// `String`-keyed ones always have.
// ---------------------------------------------------------------------------------------------

/**
 * `Scope.of(HomeScope) { params -> ... }` - this scope's actual container/thread/emitter setup,
 * with a concrete [P] instead of the untyped `Parameters` bag, handed out as a plain `val` you can
 * define once and import wherever it needs composing into a [ru.alexey.event.threads.scopeholder.scopeHolder]
 * (via `include`) - the multi-module counterpart to
 * [ru.alexey.event.threads.scopeholder.ScopeHolderBuilder.scopeEmbedded]'s single-block, declare-
 * and-build-right-here style (see that overload's KDoc) for a scope owned/composed from more than
 * one place.
 *
 * Anchored on [Scope], not [ScopeKey]: this builds the scope's actual content, the thing [Scope]
 * itself is - [target] only tells it *whose* content that is (its [ScopeKey.identity] is where
 * this gets attached). [ScopeKey] stays purely an identity/topology vocabulary
 * ([ScopeKey.by]/[ScopeKey.extend]/[ScopeKey.from]); nothing here has to go through, or leak into,
 * that DSL. Whether [target] is actually declared anywhere is decided independently, purely by
 * those three - see [ScopeKeyBody]'s KDoc.
 *
 * Multiple `of` calls for the same [target] (e.g. one from the owning module, another layering on
 * top from a composition root - the same use case [ScopeKey.extend] serves for topology) all run
 * against the same fresh `ScopeBuilder`, in `include` order.
 *
 * The cast is safe by construction: [P] is only ever supplied at the matching
 * `ScopeHolder.load(target: ScopeKey<P>, params: P)` call site for the same [target].
 */
@Suppress("UNCHECKED_CAST")
fun <P : Any> Scope.Companion.of(
    target: ScopeKey<P>,
    block: ScopeBuilder.(P) -> Unit,
): ScopeKeyBody<P> = ScopeKeyBody(target.identity) { params -> block(this, params as P) }

/** `ScopeKey.by(HomeScope) { ... }` - declare a fragment for a type you own: topology only
 * (`dependsOn`/`implements`/`consume`/`legacyAlias`), see [ScopeKeyBuilder]. Pair with a separate
 * [Scope.of] call for [target]'s actual content. Registers a real (if empty) `scopeEmbedded`
 * for [target]'s identity even if [block] is empty and/or no [Scope.of] is ever contributed
 * for it - see [ScopeKeyBuilder]'s KDoc. */
fun <P : Any> ScopeKey.Companion.by(
    target: ScopeKey<P>,
    block: ScopeKeyBuilder<P>.() -> Unit = {}
): ScopeKeyFragment<P> = ScopeKeyBuilder<P>(target.identity, declaresScope = true).apply(block).build()

/** `ScopeKey.extend(HomeScope) { ... }` - contribute relations to a scope you *don't* own,
 * referencing its already-declared marker `object` directly (no string). Unlike [by], never
 * registers a `scopeEmbedded` on its own - the target must already be declared (via [by]/[from],
 * typed or old-style) somewhere else in the same holder. */
fun <P : Any> ScopeKey.Companion.extend(
    target: ScopeKey<P>,
    block: ScopeKeyBuilder<P>.() -> Unit = {}
): ScopeKeyFragment<P> = ScopeKeyBuilder<P>(target.identity, declaresScope = false).apply(block).build()

/** `ScopeKey.extend(previousFragment) { ... }` - chain further contributions onto a fragment `val`
 * you already built (from [by] or another [extend]), e.g. layering an app-level composition root's
 * wiring on top of a feature module's own. Always non-declaring, like the [ScopeKey] overload of
 * [extend] - chain onto a [by] fragment (directly or transitively) if a `scopeEmbedded` is needed. */
fun <P : Any> ScopeKey.Companion.extend(
    target: ScopeKeyFragment<P>,
    block: ScopeKeyBuilder<P>.() -> Unit = {}
): ScopeKeyFragment<P> = ScopeKeyBuilder<P>(target.identity, declaresScope = false).apply(block).build()

/** `ScopeKey.from("legacyScopeName") { ... }` - the backward-compat bridge: contributes relations
 * (like [extend], `declaresScope = false`) to a scope still identified, and actually registered
 * (`scopeEmbedded`), by a raw string on the old `ScopeHolderBuilder` API - `from` only lets that
 * existing registration be *referenced* from typed `dependsOn`/`implements`/`consume` elsewhere,
 * it does not itself create one. There's no typed `ScopeKey<Unit>` object here to hand to
 * [Scope.of] either - old-style scopes have no typed params anyway, they go through the
 * untyped `Parameters` bag via the old `ScopeHolderBuilder.scopeEmbedded(key: String, ...)`. */
@Deprecated(
    "Migrate the target scope to `object Foo : ScopeKey<P>` and use ScopeKey.by/extend instead.",
    level = DeprecationLevel.WARNING
)
fun ScopeKey.Companion.from(
    legacyName: String,
    block: ScopeKeyBuilder<Unit>.() -> Unit = {}
): ScopeKeyFragment<Unit> = ScopeKeyBuilder<Unit>(legacyName, declaresScope = false).apply(block).build()

// Boxes a typed `P` inside the untyped `Parameters` bag under a single, fixed, private key -
// never a per-scope one - so writing it (at `ScopeHolder.load(target, params)`) and reading it
// back (inside the registered `scopeEmbedded` body) always agree on where to look, regardless of
// what P actually is.
private object TypedParamsKey

/** Boxes [params] into a [Parameters] bag the way [ScopeHolder.load]/[ScopeHolder.findOrLoad]'s
 * typed overloads (and the typed `scope()` composable in `event-thread-compose`) do - exposed so
 * another module bridging its own String-keyed, `Parameters`-based API onto [ScopeKey] can reuse
 * the exact same boxing instead of inventing a second one. */
fun <P : Any> typedParameters(params: P): Parameters = mapOf(TypedParamsKey::class to { params })

/** Unboxes what [typedParameters] boxed, or `Unit` if nothing was. */
@Suppress("UNCHECKED_CAST")
fun Parameters.resolveTyped(): Any = this[TypedParamsKey::class]?.invoke() ?: Unit

private val pendingContributions = mutableMapOf<ScopeHolderBuilder, MutableList<ScopeKeyContribution>>()

/**
 * Registers contributions built via [ScopeKey.by]/[ScopeKey.extend]/[ScopeKey.from] (topology) and
 * [Scope.of] (content). Call this as many times as convenient within one `scopeHolder { }`
 * block (e.g. once per feature module) - contributions sharing an identity merge regardless of how
 * many `include` calls contributed them, since actual registration on the real builder is deferred
 * (via [ScopeHolderBuilder.onBuild]) until [ScopeHolderBuilder.build] runs, once every `include`
 * call has been collected. Calling `include` outside a `scopeHolder { }`/`build()` call is a no-op
 * - nothing is silently dropped in the sense of leaking, but nothing is registered either.
 */
fun ScopeHolderBuilder.include(vararg contributions: ScopeKeyContribution) {
    val list = pendingContributions.getOrPut(this) {
        mutableListOf<ScopeKeyContribution>().also {
            onBuild { applyTypedFragments(this, pendingContributions.remove(this).orEmpty()) }
        }
    }
    list += contributions
}

private fun applyTypedFragments(builder: ScopeHolderBuilder, contributions: List<ScopeKeyContribution>) {
    val fragments = contributions.filterIsInstance<ScopeKeyFragment<*>>()
    val bodies = contributions.filterIsInstance<ScopeKeyBody<*>>()
    val bodiesByIdentity = bodies.groupBy { it.identity }

    val byIdentity = fragments.groupBy { it.identity }
    for ((identity, group) in byIdentity) {
        // Only register a `scopeEmbedded` if some fragment in this group actually declares the
        // scope (ScopeKey.by/from) - an extend-only group means the real registration lives
        // elsewhere (old-style `scopeEmbedded`, or a `by` fragment contributed separately), and
        // registering an empty one here would silently replace it (ScopeHolderBuilder.scopeEmbedded
        // assigns rather than merges). A declaring group still registers even with zero bodies
        // (e.g. `ScopeKey.by(Work) { implements(TodoListBase) }`, no `Scope.of` at all) - the
        // scope must still resolve to *something*, same as today's `scopeEmbedded("Work") {}`.
        if (group.any { it.declaresScope }) {
            val ownBodies = bodiesByIdentity[identity].orEmpty().map { it.block }
            val embed: ScopeBuilder.(Parameters) -> Unit = { parameters ->
                val params = parameters.resolveTyped()
                ownBodies.forEach { body -> body(this, params) }
            }
            builder.scopeEmbedded(identity, embed)
            // See ScopeKeyBuilder.legacyAlias's KDoc for what this does and doesn't guarantee.
            group.flatMap { it.legacyAliases }.forEach { alias -> builder.scopeEmbedded(alias, embed) }
        }
        group.flatMap { it.dependsOn }.distinct().takeIf { it.isNotEmpty() }?.let { deps ->
            with(builder) { identity dependsOn deps }
        }
        group.flatMap { it.implementsSet }.distinct().takeIf { it.isNotEmpty() }?.let { impls ->
            with(builder) { identity implements impls }
        }
    }

    // Aggregated globally, not per-identity: ScopeHolderBuilder's own `KClass.consume` assigns
    // (`external[this] = receivers`) rather than merges on repeat calls for the same event class,
    // so two typed scopes independently declaring `consume<SameEvent>()` must be folded into one
    // call listing both, or the second registration would silently drop the first.
    fragments
        .flatMap { fragment -> fragment.consumes.map { it to fragment.identity } }
        .groupBy({ it.first }, { it.second })
        .forEach { (eventClass, identities) ->
            with(builder) { eventClass consume identities.distinct() }
        }
}

/** [load] for a scope declaring real params. */
fun <P : Any> ScopeHolder.load(target: ScopeKey<P>, params: P): Scope? =
    load(target.identity) { typedParameters(params) }

/** [load] for a `ScopeKey<Unit>` - no params to supply. */
fun ScopeHolder.load(target: ScopeKey<Unit>): Scope? = load(target.identity)

fun ScopeHolder.find(target: ScopeKey<*>): Scope? = find(target.identity)

fun ScopeHolder.free(target: ScopeKey<*>) = free(target.identity)

fun ScopeHolder.findOrLoad(target: ScopeKey<Unit>): Scope = findOrLoad(target.identity)

fun <P : Any> ScopeHolder.findOrLoad(target: ScopeKey<P>, params: P): Scope =
    findOrLoad(target.identity) { typedParameters(params) }
