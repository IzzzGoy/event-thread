package ru.alexey.event.threads.scopeholder

import ru.alexey.event.threads.Builder
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.ScopeBuilder
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.DummyProvider
import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass

class ScopeHolderBuilder {

    private val factories: MutableMap<String, (Parameters, List<ScopeBuilder>) -> ScopeBuilder> = mutableMapOf()
    private val external: MutableMap<KClass<out Event>, List<String>> = mutableMapOf()
    private val dependencies: MutableMap<String, List<String>> = mutableMapOf()
    private val implementations: MutableMap<String, List<String>> = mutableMapOf()
    private var dependencyProvider: DependencyProvider = DummyProvider()

    @Builder
    fun dependencyProvider(provider: DependencyProvider) {
        this.dependencyProvider = provider
    }

    /**
     * Same registration as [scopeEmbedded], but returns [key] so it can be chained straight
     * into [dependsOn]/[implements] - lets a feature module export its scope setup as a plain
     * `ScopeBuilder.(Parameters) -> Unit` extension function (e.g. `ScopeBuilder::provideFooScope`)
     * that the composition root wires in without needing to know its internals.
     */
    fun scope(key: String, init: ScopeBuilder.(Parameters) -> Unit): String {
        scopeEmbedded(key, init)
        return key
    }

    fun build(): ScopeHolder {
        return ScopeHolder(
            external = external,
            factories = factories,
            dependencies = dependencies,
            implementations = implementations,
            dependencyProvider = dependencyProvider
        )
    }

    fun scopeEmbedded(key: String, init: ScopeBuilder.(Parameters) -> Unit) {
        factories[key] = { params, parents ->
            ScopeBuilder(key, parents, dependencyProvider).apply { init(params) }
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