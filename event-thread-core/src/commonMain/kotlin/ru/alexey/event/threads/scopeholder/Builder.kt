package ru.alexey.event.threads.scopeholder

import ru.alexey.event.threads.Event
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.scopeBuilder
import kotlin.reflect.KClass

class ScopeHolderBuilder {

    private val factories: MutableMap<String, (Parameters, List<ScopeBuilder>) -> ScopeBuilder> = mutableMapOf()
    private val external: MutableMap<KClass<out Event>, List<String>> = mutableMapOf()
    private val dependencies: MutableMap<String, List<String>> = mutableMapOf()
    private val implementations: MutableMap<String, List<String>> = mutableMapOf()

    /*fun scope(key: String, block: (String, Parameters) -> ScopeBuilder): String {
        factories[key] = { params, parents ->
            block(key, params)
        }
        return key
    }*/

    fun scope(key: String, block: (String, List<ScopeBuilder>) -> ((Parameters) -> ScopeBuilder)): String {
        factories[key] = { params, parents ->
            block(key, parents)(params)
        }
        return key
    }


    fun build(): ScopeHolder {
        return ScopeHolder(
            external = external,
            factories = factories,
            dependencies = dependencies,
            implementations = implementations
        )
    }

    fun scopeEmbedded(key: String, init: ScopeBuilder.(Parameters) -> Unit) {
        factories[key] = { params, parents ->
            ScopeBuilder(key, parents).apply { init(params) }
        }
    }

    fun external(key: KClass<out Event>, receivers: List<String>) {
        external[key] = receivers
    }

    infix fun KClass<out Event>.consume(receiver: String) {
        external[this] = listOf(receiver)
    }

    infix fun KClass<out Event>.consume(receivers: List<String>) {
        external[this] = receivers
    }

    infix fun KClass<out Event>.consume(receivers: () -> List<String>) {
        external[this] = receivers()
    }

    infix fun String.dependsOn(key: String) {
        dependencies[this] = listOf(key)
    }

    infix fun String.dependsOn(key: List<String>) {
        dependencies[this] = key
    }

    infix fun String.dependsOn(keys: () -> List<String>) {
        dependencies[this] = keys()
    }

    infix fun String.implements(key: String) {
        implementations[this] = listOf(key)
    }

    infix fun String.implements(key: List<String>) {
        implementations[this] = key
    }

    infix fun String.implements(keys: () -> List<String>) {
        implementations[this] = keys()
    }
}


fun scopeHolder(block: ScopeHolderBuilder.() -> Unit): ScopeHolder {
    return ScopeHolderBuilder().apply(block).build()
}