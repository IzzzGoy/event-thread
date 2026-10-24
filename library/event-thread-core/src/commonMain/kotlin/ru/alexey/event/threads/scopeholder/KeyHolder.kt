package ru.alexey.event.threads.scopeholder

/** Anything identified by a stable string [key] - a [ru.alexey.event.threads.Scope]'s own key, or
 * a type-safe alternative to passing scope names as raw strings into [ScopeHolder]'s `load`/
 * `free` overloads. */
interface KeyHolder {
    val key: String
}
