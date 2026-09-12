package ru.alexey.event.threads.cache

import ru.alexey.event.threads.ContextProvider

/**
 * Overrides where [pathToJSON]/[pathToBinary] resolve their cache directory to, bypassing
 * [ContextProvider]/`Context` entirely when set. Exists for tests: a real `android.content.
 * Context` isn't available in a plain local unit test (no Robolectric here), so a
 * `cacheJsonResource`/`cacheBinaryResource`-backed container can't otherwise be built without one
 * - set this to a temp directory instead. Falls back to [ContextProvider]'s `filesDir` when never
 * set, so production code (which does have a real `Context`, via `ContextProvider`) is unaffected.
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
        get() = override?.invoke() ?: ContextProvider.provider().filesDir.path
}

actual fun pathToJSON(key: String): String {
    return "${CacheDirectoryProvider.path}/$key.json"
}

actual fun pathToBinary(key: String): String {
    return "${CacheDirectoryProvider.path}/$key"
}

