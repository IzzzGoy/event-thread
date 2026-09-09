package ru.alexey.event.threads

import androidx.compose.runtime.staticCompositionLocalOf
import ru.alexey.event.threads.scopeholder.ScopeHolder
import kotlin.jvm.JvmInline

/** The [ScopeHolder] for the current composition - provided once at the root via [ru.alexey.
 * event.threads.ScopeHolder] (the composable), read by [scope]/[ru.alexey.event.threads.navgraph.
 * NavGraph] when no explicit `scopeHolder`/`holder` argument is passed. */
val LocalScopeHolder = staticCompositionLocalOf<ScopeHolder> { error("Scope holder not provided!") }

/** The nearest enclosing [Scope] - provided by [scope] for its `content`, read via
 * `LocalScope.current` (or the `by scope`/`by LocalScope.current` delegate on [Scope]) wherever a
 * composable needs to resolve that scope's containers or dispatch events into it. */
val LocalScope = staticCompositionLocalOf<Scope> { error("Scope not provided!") }

/** Reference-counts how many mounted composables currently want a given named scope alive, so
 * [scope]/[ru.alexey.event.threads.navgraph.NavGraph] only call [ScopeHolder.free] once the last
 * one unmounts - e.g. two widgets both mounting `scope("Work") { }` at once shouldn't free
 * `"Work"` when only one of them leaves composition. */
@JvmInline
value class ScopeCounter(
    private val counts: MutableMap<String, Int>
) {
    /** Marks one more mounted user of [name]. */
    fun register(name: String) {
        counts[name] = counts.getOrElse(name) { 0 } + 1
    }

    /** Marks one mounted user of [name] as gone; returns `true` once the count reaches zero
     * (meaning the caller should now free the scope). */
    fun unregister(name: String): Boolean {
        val next = counts.getOrElse(name) { 1 } - 1
        return if (next == 0) {
            counts.remove(name)
            true
        } else {
            counts[name] = next
            false
        }
    }
}

internal val LocalScopeCounter = staticCompositionLocalOf<ScopeCounter> { error("Init ScopeHolder first!") }