package ru.alexey.event.threads.cache

import io.github.xxfast.kstore.KStore
import io.github.xxfast.kstore.storage.storeOf
import kotlinx.serialization.Serializable

actual inline fun<reified T: @Serializable Any> storeJSONOf(key: String, default: T): KStore<T> = storeOf(
    pathToJSON(key),
    default = default
)