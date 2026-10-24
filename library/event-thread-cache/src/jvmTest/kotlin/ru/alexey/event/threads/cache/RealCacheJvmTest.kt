package ru.alexey.event.threads.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
private data class Note(val text: String = "", val priority: Int = 0)

/**
 * Exercises the real, file-backed [jsonCache]/[binaryCache] (JVM `actual`s in `RealCache.kt`)
 * against an actual temp directory - [CacheResourceTest] covers [CacheResource]'s own contract
 * with a fake [Cache], this covers the real I/O underneath it, including
 * [cacheJsonResource]/[cacheBinaryResource]'s fallback-on-missing/corrupt-file behavior via
 * [CacheDirectoryProvider] (so these tests never touch the real per-user AppDirs directory).
 */
class RealCacheJvmTest {

    private val tempDir: File = Files.createTempDirectory("event-thread-cache-test").toFile()

    @AfterTest
    fun tearDown() {
        CacheDirectoryProvider.reset()
        tempDir.deleteRecursively()
    }

    @Test
    fun jsonCacheRoundTripsThroughARealFile() {
        val cache = jsonCache(File(tempDir, "note.json").path, Json, serializer<Note>())

        cache.write(Note("hello", 3))

        assertEquals(Note("hello", 3), cache.load())
    }

    @Test
    fun jsonCacheLoadThrowsWhenTheFileDoesNotExist() {
        val cache = jsonCache(File(tempDir, "missing.json").path, Json, serializer<Note>())

        assertFailsWith<Exception> { cache.load() }
    }

    @Test
    fun jsonCacheWriteCreatesMissingParentDirectories() {
        // A directory chain that does not exist yet - simulates the very first write on a
        // machine where AppDirsFactory's computed directory was never actually created (see
        // PathTo.kt/RealCache.kt's write() fix). Before that fix this threw; now it must succeed.
        val path = File(tempDir, "fresh/nested/dir/note.json").path
        val cache = jsonCache(path, Json, serializer<Note>())

        cache.write(Note("first launch", 1))

        assertEquals(Note("first launch", 1), cache.load())
    }

    @Test
    fun binaryCacheRoundTripsThroughARealFile() {
        val cache = binaryCache(File(tempDir, "note.bin").path, Cbor, serializer<Note>())

        cache.write(Note("binary", 7))

        assertEquals(Note("binary", 7), cache.load())
    }

    @Test
    fun binaryCacheWriteCreatesMissingParentDirectories() {
        // A directory chain that does not exist yet - simulates the very first write on a
        // machine where AppDirsFactory's computed directory was never actually created (see
        // PathTo.kt/RealCache.kt's write() fix). Before that fix this threw; now it must succeed.
        val path = File(tempDir, "fresh/nested/dir/note.bin").path
        val cache = binaryCache(path, Cbor, serializer<Note>())

        cache.write(Note("first launch", 1))

        assertEquals(Note("first launch", 1), cache.load())
    }

    @Test
    fun cacheJsonResourceFallsBackToInitialAndPersistsItOnFirstUse() {
        CacheDirectoryProvider { File(tempDir, "fresh/does/not/exist/yet").path }

        val resource = cacheJsonResource("notes", Note("default"), Json)
        assertEquals(Note("default"), resource.value)

        // Constructing it again (simulating a restart) must read back what was just persisted,
        // not silently fall back to a *different* initial value passed this time.
        val reloaded = cacheJsonResource("notes", Note("should not be used"), Json)
        assertEquals(Note("default"), reloaded.value)
    }

    @Test
    fun cacheJsonResourceHealsAndPersistsOverACorruptedFile() {
        val dir = File(tempDir, "corrupted").apply { mkdirs() }
        CacheDirectoryProvider { dir.path }
        File(dir, "notes.json").writeText("{ not valid json at all")

        val resource = cacheJsonResource("notes", Note("fallback"), Json)
        assertEquals(Note("fallback"), resource.value)

        // The corrupt file is overwritten with the fallback value, not left corrupt - the next
        // load (e.g. after a real restart) must see the healed value too, not fail again.
        val reloaded = cacheJsonResource("notes", Note("should not be used"), Json)
        assertEquals(Note("fallback"), reloaded.value)
    }

    @Test
    fun cacheBinaryResourceRoundTripsAcrossASimulatedRestart() {
        CacheDirectoryProvider { tempDir.path }

        val resource = cacheBinaryResource("counter", Note("start"), Cbor)
        assertEquals(Note("start"), resource.value)

        val reloaded = cacheBinaryResource("counter", Note("should not be used"), Cbor)
        assertEquals(Note("start"), reloaded.value)
    }
}
