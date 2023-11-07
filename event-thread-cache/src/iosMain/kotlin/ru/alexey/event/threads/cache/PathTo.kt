package ru.alexey.event.threads.cache

import platform.Foundation.NSHomeDirectory


actual fun pathToJSON(key: String): String {
    return  "${NSHomeDirectory()}/$key.json"
}

actual fun pathToBinary(key: String): String {
    return "${NSHomeDirectory()}/$key"
}