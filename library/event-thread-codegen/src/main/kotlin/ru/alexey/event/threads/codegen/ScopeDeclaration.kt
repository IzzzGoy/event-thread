package ru.alexey.event.threads.codegen

import org.gradle.api.Named
import org.gradle.api.provider.Property
import javax.inject.Inject

/** One `scopes { register("Todo") { ... } }` entry in [EventThreadCodegenExtension]. */
abstract class ScopeDeclaration @Inject constructor(private val name: String) : Named {
    override fun getName(): String = name

    /** Emitted as the generated class's KDoc. */
    abstract val description: Property<String>
}
