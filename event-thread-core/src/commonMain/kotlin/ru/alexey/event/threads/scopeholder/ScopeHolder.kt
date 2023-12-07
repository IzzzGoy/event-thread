package ru.alexey.event.threads.scopeholder

import ru.alexey.event.threads.Event
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.scopeBuilder
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
        scope(keyHolder.key, block = { block(keyHolder) })
    }

    fun scope(key: String, block: () -> Scope) {
        factories[key] = block
    }

    fun scopeEmbedded(key: String, init: ScopeBuilder.() -> Unit) {
        factories[key] = {
            scopeBuilder(key) {
                init()
            }
        }
    }

    fun scoped(key: String, block: (String) -> Scope) {
        factories[key] = { block(key) }
    }

    infix fun load(keyHolder: KeyHolder): Scope? = load(keyHolder.key)

    infix fun load(key: String): Scope? {
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

    infix fun free(keyHolder: KeyHolder) {
        active.removeAll { it.key == keyHolder.key }
    }

    infix fun free(key: String) {
        active.removeAll { it.key == key }
    }

    operator fun plus(event: Event) {
        val scopes: List<Scope> = external.keys.find {
            it.isInstance(event)
        }?.let { eventKClass ->
            val keys = external[eventKClass] ?: emptyList()
            active.filter { it.key in keys }
        } ?: active


        for (scope in scopes) {
            scope + event
        }
    }

    infix fun find(key: String): Scope? = active.find { it.key == key }
    infix fun findOrLoad(key: String): Scope = find(key) ?: load(key) ?: error("Scope with name: $key not found")
}

fun scopeHolder(vararg external: Pair<KClass<out Event>, List<String>>, block: ScopeHolder.() -> Unit): ScopeHolder {
    return ScopeHolder(
        external.toMap()
    ).apply(block)
}
