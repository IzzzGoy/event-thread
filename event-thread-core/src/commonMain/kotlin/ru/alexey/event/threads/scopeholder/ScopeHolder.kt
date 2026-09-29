package ru.alexey.event.threads.scopeholder

import ru.alexey.event.threads.Event
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass
import kotlin.reflect.typeOf

class ScopeHolder(
    val external: Map<KClass<out Event>, List<String>>,
    private val factories: Map<String, (Parameters, List<ScopeBuilder>) -> ScopeBuilder>,
    val dependencies: Map<String, List<String>> = emptyMap(),
    private val implementations: Map<String, List<String>> = emptyMap()
) {

    private val active: MutableSet<Scope> = mutableSetOf()

    val activeMetadata
        get() = active.associate { it.key to it.metadata }

    private fun getAllDeps(key: String, params: () -> Parameters): List<ScopeBuilder> {
        return implementations.getOrElse(key, ::emptyList).mapNotNull {
            factories[it]?.invoke(params(), getAllDeps(it, params))
        }
    }

    private fun loadInternal(key: String, params: () -> Parameters = ::emptyMap): Scope? {
        return factories[key]?.let {
            val scope = it(params(), getAllDeps(key, params))

            //it(params()).scope
            scope.scope
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

    fun load(key: String, params: () -> Parameters): Scope? {
        return loadInternal(key, params)
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
    infix fun findOrLoad(key: String): Scope =
        find(key) ?: load(key) ?: error("Scope with name: $key not found")

    fun findOrLoad(key: String, params: () -> Parameters): Scope =
        find(key) ?: load(key, params) ?: error("Scope with name: $key not found")
}


