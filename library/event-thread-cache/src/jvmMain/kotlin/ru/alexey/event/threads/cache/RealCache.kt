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
import okio.use


@OptIn(ExperimentalSerializationApi::class)
actual fun<T> jsonCache(path: String, json: Json, serializer: KSerializer<T>): Cache<T> {
    return object : Cache<T> {
        private val source
            get() = FileSystem.SYSTEM.source(path.toPath())

        private val sink
            get() = FileSystem.SYSTEM.sink(path.toPath())

        override fun load(): T
                = source.buffer().use {
            json.decodeFromBufferedSource(serializer, it)
        }

        override fun write(obj: T) {
            // AppDirsFactory (see PathTo.kt) only computes where the app's data directory *should*
            // be - it never creates it, and okio's sink() (like a plain FileOutputStream) requires
            // the parent directory to already exist. Without this, the very first write on a
            // machine with no prior install throws uncaught - createDirectories(mustCreate = false)
            // is a no-op once the directory exists, so this costs nothing on every later write.
            path.toPath().parent?.let { FileSystem.SYSTEM.createDirectories(it, mustCreate = false) }
            sink.buffer().use {
                json.encodeToBufferedSink(serializer, obj, it)
            }
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
                = source.buffer().use {
            // Not `.readByteArray()` outside a `.use{}` - the previous version left the file
            // descriptor open for the lifetime of the returned Source object (only closed by GC
            // finalization, if at all), leaking one handle per `load()` call.
            cbor.decodeFromByteArray(serializer, it.readByteArray())
        }

        override fun write(obj: T) {
            path.toPath().parent?.let { FileSystem.SYSTEM.createDirectories(it, mustCreate = false) }
            // `.use { }`, not a bare `sink.buffer().write(...)` - writing to a BufferedSink only
            // fills its in-memory buffer; without close() (which `use` guarantees) flushing it to
            // the underlying file, the write was silently never actually persisted at all - caught
            // by RealCacheJvmTest's binaryCache round-trip test failing with a CBOR decoding
            // error on the (empty) file it had supposedly just written.
            sink.buffer().use {
                it.write(cbor.encodeToByteArray(serializer, obj).toByteString())
            }
        }
    }
}