package ru.alexey.event.threads.cache

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*


@OptIn(ExperimentalForeignApi::class)
actual fun pathToJSON(key: String): String {
    val documentDirectory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSCachesDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory).path + "/$key.json"
}

@OptIn(ExperimentalForeignApi::class)
actual fun pathToBinary(key: String): String {

    val documentDirectory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSCachesDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory).path + "/$key"
}