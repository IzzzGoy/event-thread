package ru.alexey.event.threads.cache

import android.content.Context


object ContextProvider {
    private var _provider: (() -> Context)? = null
    val provider: () -> Context
        get() = _provider ?: error("Missing Context")

    operator fun invoke(block: () -> Context) {
        _provider = block
    }
}

actual fun pathToJSON(key: String): String {
    return "${ContextProvider.provider().filesDir.path}/$key.json"
}

actual fun pathToBinary(key: String): String {
    return "${ContextProvider.provider().filesDir.path}/$key"
}

