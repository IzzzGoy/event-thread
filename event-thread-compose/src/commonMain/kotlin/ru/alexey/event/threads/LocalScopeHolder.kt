package ru.alexey.event.threads

import androidx.compose.runtime.staticCompositionLocalOf
import ru.alexey.event.threads.scopeholder.ScopeHolder

val LocalScopeHolder = staticCompositionLocalOf<ScopeHolder> { error("Scope holder not provided!") }
val LocalScope = staticCompositionLocalOf<Scope> { error("Scope not provided!") }