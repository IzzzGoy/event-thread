package ru.alexey.event.threads.codegen.source

import org.gradle.api.file.FileCollection

/** What the plugin knows about the consuming project that a [ConfigSource] can't find itself. */
class ConfigSourceContext internal constructor(
    /** Resource roots of the Kotlin source set generated code lands in (`commonMain` for
     * multiplatform, `main` for JVM/Android), in order. Empty without a Kotlin plugin. */
    val resourceDirectories: FileCollection,
)
