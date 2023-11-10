package ru.alexey.event.threads.scopeholder

import ru.alexey.event.threads.Event
import ru.alexey.event.threads.Scope
import kotlin.reflect.KClass

class ScopeHolder(
    private val external: Map<KClass<out Event>, List<String>>
) {
    private val factories: MutableMap<String, () -> Scope> = mutableMapOf()
    private val active: MutableList<Scope> = mutableListOf()

    fun scope(keyHolder: KeyHolder, block: () -> Scope) {
        scope(keyHolder.key, block)
    }

    fun scoped(keyHolder: KeyHolder, block: (KeyHolder) -> Scope) {
        scope(keyHolder.key) { block(keyHolder) }
    }

    fun scope(key: String, block: () -> Scope) {
        factories[key] = block
    }

    fun scoped(key: String, block: (String) -> Scope) {
        factories[key] = { block(key) }
    }

    fun load(keyHolder: KeyHolder): Scope? = load(keyHolder.key)

    fun load(key: String): Scope? {
        val scope =  factories[key]?.let {
            val scope = it()
            active += scope
            scope
        }

        scope?.eventBus?.external<Event> { event ->
            external.filterKeys { it.isInstance(event) }.values.forEach { receivers ->
                active.filter { it.key in receivers && scope.key !in receivers }.forEach { it + event }
            }
        }

        return scope
    }

    fun free(keyHolder: KeyHolder) {
        active.removeAll { it.key == keyHolder.key }
    }

    fun free(key: String) {
        active.removeAll { it.key == key }
    }

    operator fun plus(event: Event) {
        for (scope in active) {
            scope + event
        }
    }
}

fun scopeHolder(vararg external: Pair<KClass<out Event>, List<String>>, block: ScopeHolder.() -> Unit): ScopeHolder {
    return ScopeHolder(
        external.toMap()
    ).apply(block)
}
