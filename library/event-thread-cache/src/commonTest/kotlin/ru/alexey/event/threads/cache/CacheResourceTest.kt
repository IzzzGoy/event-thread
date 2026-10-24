package ru.alexey.event.threads.cache

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** In-memory [Cache] test double - no real I/O, so [CacheResourceTest] can pin down
 * [CacheResource]'s own contract (mutex serialization, what happens to memory/disk state when
 * [block]/[write] throws) independently of any platform's real file-backed [jsonCache]/
 * [binaryCache] (covered instead by `RealCacheJvmTest`, against real files). */
private class FakeCache<T>(initial: T) : Cache<T> {
    var stored: T = initial
        private set
    var writeCount = 0
        private set
    var throwOnWrite: (() -> Throwable)? = null

    override fun load(): T = stored

    override fun write(obj: T) {
        throwOnWrite?.let { throw it() }
        stored = obj
        writeCount++
    }
}

class CacheResourceTest {

    @Test
    fun updateReplacesTheValueAndPersistsIt() = runTest {
        val cache = FakeCache(0)
        val resource = CacheResource(cache, MutableStateFlow(0))

        resource.update { it + 5 }

        assertEquals(5, resource.value)
        assertEquals(5, cache.stored)
        assertEquals(1, cache.writeCount)
    }

    @Test
    fun concurrentUpdatesDoNotLoseWrites() = runTest {
        val cache = FakeCache(0)
        val resource = CacheResource(cache, MutableStateFlow(0))

        val jobs = (1..100).map { async { resource.update { it + 1 } } }
        jobs.awaitAll()

        assertEquals(100, resource.value)
        assertEquals(100, cache.stored)
        assertEquals(100, cache.writeCount)
    }

    @Test
    fun aThrowingUpdateBlockPropagatesAndLeavesStateUntouched() = runTest {
        val cache = FakeCache(0)
        val resource = CacheResource(cache, MutableStateFlow(0))

        assertFailsWith<IllegalStateException> {
            resource.update { error("boom") }
        }

        // Neither the in-memory value nor the persisted one changed - a throwing transform is
        // not a partial update, it's as if update() was never called at all.
        assertEquals(0, resource.value)
        assertEquals(0, cache.stored)
        assertEquals(0, cache.writeCount)
    }

    @Test
    fun aFailingWritePropagatesAndLeavesInMemoryStateUntouched() = runTest {
        val cache = FakeCache(0)
        cache.throwOnWrite = { IllegalStateException("disk full") }
        val resource = CacheResource(cache, MutableStateFlow(0))

        assertFailsWith<IllegalStateException> {
            resource.update { it + 1 }
        }

        // The write failed, so the in-memory value must not have advanced either - otherwise
        // memory and disk would silently disagree about the container's current value.
        assertEquals(0, resource.value)
    }

    @Test
    fun aSuccessfulUpdateAfterAFailedOneStillWorks() = runTest {
        val cache = FakeCache(0)
        cache.throwOnWrite = { IllegalStateException("disk full") }
        val resource = CacheResource(cache, MutableStateFlow(0))

        assertFailsWith<IllegalStateException> { resource.update { it + 1 } }

        cache.throwOnWrite = null
        resource.update { it + 1 }

        assertEquals(1, resource.value)
        assertEquals(1, cache.stored)
    }
}
