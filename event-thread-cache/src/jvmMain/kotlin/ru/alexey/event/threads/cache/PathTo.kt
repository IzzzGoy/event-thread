package ru.alexey.event.threads.cache

import net.harawata.appdirs.AppDirsFactory

/**
 * Overrides where [pathToJSON]/[pathToBinary] resolve their directory to on the JVM, bypassing
 * [AppDirsFactory] entirely when set - mirrors the `androidMain` `CacheDirectoryProvider` (same
 * purpose: a test needs a disposable temp directory instead of the real per-user AppDirs location,
 * which is also the only place these two functions would otherwise ever point). Falls back to the
 * real AppDirs-resolved directory when never set, so production code is unaffected.
 */
object CacheDirectoryProvider {
    private var override: (() -> String)? = null

    operator fun invoke(block: () -> String) {
        override = block
    }

    /** Clears an override set via `invoke` - call from a test's teardown so it doesn't leak into
     * unrelated tests. */
    fun reset() {
        override = null
    }

    internal val path: String
        get() = override?.invoke()
            ?: AppDirsFactory.getInstance().getUserDataDir("ru.alexey.platform", "0.0.1", "Antares")
}

actual fun pathToJSON(key: String): String {
    return "${CacheDirectoryProvider.path}/$key.json"
}

actual fun pathToBinary(key: String): String {
    return "${CacheDirectoryProvider.path}/$key"
}