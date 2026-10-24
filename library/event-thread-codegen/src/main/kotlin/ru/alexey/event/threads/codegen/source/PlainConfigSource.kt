package ru.alexey.event.threads.codegen.source

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import ru.alexey.event.threads.codegen.ScopeDeclaration
import ru.alexey.event.threads.codegen.model.ScopeModel
import javax.inject.Inject

/**
 * The schema declared right in the build script:
 * ```
 * eventThreadCodegen {
 *     source<PlainConfigSource> {
 *         scopes { register("Todo") { description = "Todo list screen" } }
 *     }
 * }
 * ```
 */
abstract class PlainConfigSource @Inject constructor(
    objects: ObjectFactory,
    private val providers: ProviderFactory,
) : ConfigSource() {

    val scopes: NamedDomainObjectContainer<ScopeDeclaration> =
        objects.domainObjectContainer(ScopeDeclaration::class.java)

    fun scopes(action: Action<NamedDomainObjectContainer<ScopeDeclaration>>) {
        action.execute(scopes)
    }

    override fun schema(context: ConfigSourceContext): Provider<List<ScopeModel>> = providers.provider {
        scopes.map { ScopeModel(it.name, it.description.orNull) }
    }
}
