package ru.alexey.event.threads.codegen.source

import org.gradle.api.provider.Provider
import ru.alexey.event.threads.codegen.model.ScopeModel

/**
 * Where the scope schema comes from. Selected and configured in `eventThreadCodegen { }` with
 * `source<T> { ... }` (see [ru.alexey.event.threads.codegen.EventThreadCodegenExtension.source]),
 * which instantiates `T` through Gradle's `ObjectFactory` - so a subclass can declare abstract
 * `Property`s and `@Inject` Gradle services (`ProviderFactory`, `ProjectLayout`, ...) like any
 * managed type. Built-in: [PlainConfigSource], [ResourceConfigSource], [UrlConfigSource]; any
 * other subclass - in a build script, `buildSrc` or another plugin - works the same way.
 *
 * Implementing one: return the schema lazily, and read anything outside the build script through
 * a Gradle provider that the configuration cache tracks - `ProviderFactory.fileContents` for a
 * file, `ProviderFactory.of` (a `ValueSource`) for anything else. A plain `File.readText()` inside
 * `provider { }` would leave a reused configuration cache serving a stale schema.
 */
abstract class ConfigSource {
    abstract fun schema(context: ConfigSourceContext): Provider<List<ScopeModel>>
}
