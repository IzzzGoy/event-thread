package ru.alexey.event.threads.cache

import ru.alexey.thread.ContextProvider

actual fun pathToJSON(key: String): String {
    return "${ContextProvider.provider().filesDir.path}/$key.json"
}

actual fun pathToBinary(key: String): String {
    return "${ContextProvider.provider().filesDir.path}/$key"
}

