package ru.alexey.event.threads.cache

import io.github.xxfast.kstore.KStore
import kotlinx.serialization.Serializable

expect inline fun<reified T: @Serializable Any> storeJSONOf(key: String, default: T): KStore<T>