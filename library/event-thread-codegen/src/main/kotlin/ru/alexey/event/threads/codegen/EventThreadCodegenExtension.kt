package ru.alexey.event.threads.codegen

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import ru.alexey.event.threads.codegen.source.ConfigSource
import javax.inject.Inject

/**
 * ```
 * eventThreadCodegen {
 *     packageName = "com.example.scopes"
 *
 *     // one source - any ConfigSource subclass, e.g.
 *     source<PlainConfigSource> { scopes { register("Todo") { description = "Todo list screen" } } }
 *     source<ResourceConfigSource> { path = "event-thread/scopes.json" }
 *     source<UrlConfigSource> { url = "https://example.com/event-thread/scopes.json" }
 * }
 * ```
 */
abstract class EventThreadCodegenExtension @Inject constructor(private val objects: ObjectFactory) {

    /** Package every generated scope is placed in. Required. */
    abstract val packageName: Property<String>

    /** Adds event-thread-core as an `api` dependency of the source set generated code lands in -
     * generated scopes extend core types, so they can't compile without it. Default `true`; turn
     * off to manage the dependency yourself. */
    abstract val addCoreDependency: Property<Boolean>

    /** Version of `io.github.izzzgoy:event-thread-core` to add. Defaults to this plugin's own
     * version - plugin and core are released together. */
    abstract val coreVersion: Property<String>

    /** Also generates test support into the test source set - per scope a `<Name>ScopeContract`
     * (extend it with your bindings) and a `<Name>ScopeProbe` - and adds `event-thread-test` and
     * `kotlin-test` to it. Default `true`. */
    abstract val generateTests: Property<Boolean>

    /** Generates `<Name>RemoteApiKtor` - a Ktor implementation - for each remote API whose
     * endpoints are all HTTP calls, makes it the bindings' default, and adds `event-thread-network`
     * (with `addCoreDependency`) when the schema has such an API. Default `true`. */
    abstract val generateKtor: Property<Boolean>

    /** Also generates Compose entry points per scope - `<Name>ScopeContent(params) { ... }` and
     * `current<Name>Scope()` - and adds `event-thread-compose` (with `addCoreDependency`). Default
     * `true` when the Kotlin Compose compiler plugin is applied, else `false`. */
    abstract val generateCompose: Property<Boolean>

    /** Schema warnings (e.g. a cascade loop) fail the build instead of being logged. Default `false`. */
    abstract val strictSchema: Property<Boolean>

    /** The schema as last released - `checkScopesCompatibility` (part of `check`) fails if the
     * current schema's `version` isn't bumped as its changes require (MAJOR/MINOR/PATCH). Unset: no
     * check. Typically a copy of the schema committed at each release. */
    abstract val baseline: RegularFileProperty

    internal val selectedSource: Property<ConfigSource> = objects.property(ConfigSource::class.java)
    private var selectedType: Class<out ConfigSource>? = null

    /**
     * Selects [T] as the schema source and configures it - `source<ResourceConfigSource> { }`.
     * [T] is any [ConfigSource] subclass; it's created through Gradle's `ObjectFactory` on first
     * use, and calling this again with the same type configures that same instance.
     */
    inline fun <reified T : ConfigSource> source(action: Action<in T> = Action { }) {
        source(T::class.java, action)
    }

    /** [source] for callers without reified generics (Groovy: `source(ResourceConfigSource) { }`). */
    fun <T : ConfigSource> source(type: Class<T>, action: Action<in T>) {
        val previous = selectedType
        // Picking two different sources is almost certainly a leftover, and silently letting the
        // last one win would hide one of the schemas - fail at configuration instead.
        check(previous == null || previous == type) {
            "`$NAME` selects both ${previous!!.simpleName} and ${type.simpleName} - pick one source"
        }
        if (previous == null) {
            selectedType = type
            selectedSource.set(objects.newInstance(type))
        }
        action.execute(type.cast(selectedSource.get()))
    }

    companion object {
        const val NAME = "eventThreadCodegen"
    }
}
