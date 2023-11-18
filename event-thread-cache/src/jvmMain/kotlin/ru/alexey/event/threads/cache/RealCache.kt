package ru.alexey.event.threads.cache

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.json.okio.encodeToBufferedSink
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer


@OptIn(ExperimentalSerializationApi::class)
actual fun<T> jsonCache(path: String, json: Json, serializer: KSerializer<T>): Cache<T> {
    return object : Cache<T> {
        private val source
            get() = FileSystem.SYSTEM.source(path.toPath())

        private val sink
            get() = FileSystem.SYSTEM.sink(path.toPath())

        override fun load(): T
            = json.decodeFromBufferedSource(serializer, source.buffer())

        override fun write(obj: T) {
            json.encodeToBufferedSink(serializer, obj, sink.buffer())
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
        private val source
            get() = FileSystem.SYSTEM.source(path.toPath())

        private val sink
            get() = FileSystem.SYSTEM.sink(path.toPath())

        override fun load(): T
                = cbor.decodeFromByteArray(serializer, source.buffer().readByteArray())

        override fun write(obj: T) {
            sink.buffer().write(cbor.encodeToByteArray(serializer, obj).toByteString())
        }
    }
}