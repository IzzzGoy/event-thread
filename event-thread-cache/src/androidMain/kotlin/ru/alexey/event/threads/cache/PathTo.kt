package ru.alexey.event.threads.cache

import ru.alexey.event.threads.ContextProvider


actual fun pathToJSON(key: String): String {
    return "${ContextProvider.provider().filesDir.path}/$key.json"
}

actual fun pathToBinary(key: String): String {
    return "${ContextProvider.provider().filesDir.path}/$key"
}

