package ru.alexey.event.threads.cache

import net.harawata.appdirs.AppDirsFactory


actual fun pathToJSON(key: String): String {
    val appDir: String = AppDirsFactory.getInstance().getUserDataDir("ru.alexey.platform", "0.0.1", "Antares")
    return "$appDir/$key.json"
}

actual fun pathToBinary(key: String): String {
    val appDir: String = AppDirsFactory.getInstance().getUserDataDir("ru.alexey.platform", "0.0.1", "Antares")
    return "$appDir/$key"
}