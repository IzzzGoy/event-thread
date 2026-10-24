package ru.alexey.event.threads.scopeholder.typed

import ru.alexey.event.threads.bus.EventBus
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.Qualifier
import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass

/**
 * Base of the generated `<Name>ScopeDependencyBindings` adapters: a scope's bindings interface
 * implemented by resolving each container/handler from a [DependencyProvider], with the
 * conventions every binding relies on kept in this one place:
 * - a binding receives its arguments in declaration order - its `Deps` (if any), then the scope's
 *   params (if not `Unit`): Koin destructures them by position (`(deps, params) -> ...`),
 *   `DummyProvider` resolves them by type;
 * - the scope's bus is an [EventBus] bound under the scope's identity as qualifier, if any.
 *
 * Bind as factories: every scope instance needs its own containers, handlers and bus.
 */
abstract class DependencyProviderBindings(
    protected val dependencyProvider: DependencyProvider,
    private val scopeIdentity: String,
) {
    protected fun <T : Any> resolve(type: KClass<T>, vararg args: Any): T =
        dependencyProvider.getOrNull(type, null, parametersOf(args))
            ?: error("Scope '$scopeIdentity': no implementation registered in its DependencyProvider for ${type.simpleName}")

    protected fun resolveEventBus(): EventBus? =
        dependencyProvider.getOrNull(EventBus::class, Qualifier.named(scopeIdentity))

    private fun parametersOf(args: Array<out Any>): Parameters = buildMap {
        args.filterNot { it == Unit }.forEach { arg -> put(arg::class) { arg } }
    }
}
