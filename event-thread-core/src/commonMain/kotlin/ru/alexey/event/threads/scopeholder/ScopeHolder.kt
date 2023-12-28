package ru.alexey.event.threads.scopeholder

import ru.alexey.event.threads.Event
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.scopeBuilder
import kotlin.reflect.KClass

class ScopeHolder(
    private val external: Map<KClass<out Event>, List<String>>,
    private val factories: Map<String, () -> Scope>,
    private val dependencies: Map<String, List<String>> = emptyMap(),
) {

    private val active: MutableSet<Scope> = mutableSetOf()

    val activeMetadata
        get() =  active.map { it.key to it.eventBus.metadata }.toMap()
    val allMetadata
        get() = factories.map { it.key to it.value().eventBus.metadata }.toMap()
    private fun loadInternal(key: String): Scope? {
        return factories[key]?.let {
            it()
        }?.also { scope ->
            active += scope
            external.forEach { (k, receivers) ->
                scope.eventBus.external(k) { event ->
                    active.filter { s ->
                        s.key in receivers && scope.key !in receivers
                    }.forEach {
                        it + event
                    }
                }
            }
        }?.also {
            dependencies[it.key]?.forEach(::findOrLoad)
        }
    }


    @Deprecated("Use load(key) instead", ReplaceWith("load(key)"))
    infix fun load(keyHolder: KeyHolder): Scope? = load(keyHolder.key)

    infix fun load(key: String): Scope? {
        return loadInternal(key)
    }

    infix fun free(keyHolder: KeyHolder) {
       free(keyHolder.key)
    }

    infix fun free(key: String) {
        val scope = active.find { it.key == key } ?: return

        val depsToFree = scope.dependencies

        val activeDeps = active.filter { it.key != key }.flatMap { it.dependencies }

        depsToFree.filter {
            it !in activeDeps
        }.forEach(::free)

        active.removeAll { it.key == key }
    }

    private val Scope.dependencies: List<String>
        get() = this@ScopeHolder.dependencies[this.key] ?: emptyList()

    operator fun plus(event: Event) {

        val scopes = external.keys.filter { it.isInstance(event) }.flatMap {
            external[it] ?: emptyList()
        }.let {
            if (it.isEmpty()) {
                //broadcast case
                active
            } else {
                //external case
                active.filter { key -> key.key in it }
            }
        }

        for (scope in scopes) {
            scope + event
        }
    }

    infix fun find(key: String): Scope? = active.find { it.key == key }
    infix fun findOrLoad(key: String): Scope = find(key) ?: load(key) ?: error("Scope with name: $key not found")
}


