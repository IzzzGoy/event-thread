package ru.alexey.event.threads.scopeholder

import ru.alexey.event.threads.Event
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.scopeBuilder
import kotlin.reflect.KClass

class ScopeHolderBuilder {

    private val factories: MutableMap<String, () -> Scope> = mutableMapOf()
    private val external: MutableMap<KClass<out Event>, List<String>> = mutableMapOf()
    private val dependencies: MutableMap<String, List<String>> = mutableMapOf()

    fun scope(key: String, block: (String) -> Scope): String {
        factories[key] = { block(key) }
        return key
    }

    fun scope(key: String, block: () -> Scope) : String {
        factories[key] = block
        return key
    }

    fun build(): ScopeHolder {
        return ScopeHolder(
            external = external,
            factories = factories,
            dependencies = dependencies,
        )
    }

    fun scopeEmbedded(key: String, init: ScopeBuilder.() -> Unit) {
        factories[key] = {
            scopeBuilder(key) {
                init()
            }
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
}


fun scopeHolder(block: ScopeHolderBuilder.() -> Unit): ScopeHolder {
    return ScopeHolderBuilder().apply(block).build()
}