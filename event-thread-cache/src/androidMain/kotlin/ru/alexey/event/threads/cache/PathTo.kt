package ru.alexey.event.threads.cache

import android.content.Context


object ContextProvider {
    var provider: () -> Context = error("Missing context")
        private set

    operator fun invoke(block: () -> Context) {
        provider = block
    }
}

actual fun pathToJSON(key: String): String {
    return "${ContextProvider.provider().filesDir.path}/$key.json"
}

actual fun pathToBinary(key: String): String {
    return "${ContextProvider.provider().filesDir.path}/$key"
}

