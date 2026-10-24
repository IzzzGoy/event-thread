package ru.alexey.event.threads.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.browser.localStorage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromHexString
import kotlinx.serialization.encodeToHexString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import org.w3c.dom.get
import org.w3c.dom.set


actual fun<T> jsonCache(
    path: String,
    json: Json,
    serializer: KSerializer<T>,
) : Cache<T> {
    return object : Cache<T> {
        private val storage = localStorage
        override fun load(): T {
            return json.decodeFromString(serializer, storage[path] ?: error("Missing key `$path`"))
        }

        override fun write(obj: T) {
            storage[path] = json.encodeToString(serializer, obj)
        }

    }
}

@OptIn(ExperimentalSerializationApi::class)
actual fun<T> binaryCache(
    path: String,
    cbor: Cbor,
    serializer: KSerializer<T>,
) : Cache<T> {
    return object : Cache<T> {
        private val storage = localStorage
        override fun load(): T {
            return cbor.decodeFromHexString(serializer, storage[path] ?: error("Missing key `$path`"))
        }

        override fun write(obj: T) {
            storage[path] = cbor.encodeToHexString(serializer, obj)
        }

    }
}