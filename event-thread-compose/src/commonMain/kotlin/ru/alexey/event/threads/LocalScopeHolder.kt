package ru.alexey.event.threads

import androidx.compose.runtime.staticCompositionLocalOf
import ru.alexey.event.threads.scopeholder.ScopeHolder
import kotlin.jvm.JvmInline

val LocalScopeHolder = staticCompositionLocalOf<ScopeHolder> { error("Scope holder not provided!") }
val LocalScope = staticCompositionLocalOf<Scope> { error("Scope not provided!") }

@JvmInline
value class ScopeCounter(
    private val counts: MutableMap<String, Int>
) {
    fun register(name: String) {
        counts[name] = counts.getOrElse(name) { 0 } + 1
    }
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