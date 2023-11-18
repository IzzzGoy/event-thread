package ru.alexey.event.threads.cache

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json


expect fun<T> jsonCache(
    path: String,
    json: Json,
    serializer: KSerializer<T>,
) : Cache<T>

@OptIn(ExperimentalSerializationApi::class)
expect fun<T> binaryCache(
    path: String,
    cbor: Cbor,
    serializer: KSerializer<T>,
) : Cache<T>