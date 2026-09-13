package ru.alexey.event.threads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DefaultStateSaver] backs [LocalStateSaver] and feeds a scope's load [ru.alexey.event.threads.
 * resources.Parameters] via [StateSaver.savedParams] (see `scope()` in `ComposeExt.kt`) - none of
 * that needs a live Composition to test, it's a plain `KClass`-keyed map underneath. */
class DefaultStateSaverTest {

    @Test
    fun loadReturnsNullForAnUnsavedType() {
        val saver = DefaultStateSaver()
        assertNull(saver.load(String::class))
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val saver = DefaultStateSaver()

        saver.save(String::class, "draft text")

        assertEquals("draft text", saver.load(String::class))
    }

    @Test
    fun savingAgainOverwritesThePreviousValueForThatType() {
        val saver = DefaultStateSaver()

        saver.save(String::class, "first")
        saver.save(String::class, "second")

        assertEquals("second", saver.load(String::class))
    }

    @Test
    fun removeForgetsTheSavedValue() {
        val saver = DefaultStateSaver()
        saver.save(String::class, "draft text")

        saver.remove(String::class)

        assertNull(saver.load(String::class))
    }

    @Test
    fun differentTypesAreIndependent() {
        val saver = DefaultStateSaver()

        saver.save(String::class, "text")
        saver.save(Int::class, 42)

        assertEquals("text", saver.load(String::class))
        assertEquals(42, saver.load(Int::class))
    }

    @Test
    fun savedParamsReflectsEveryCurrentlySavedValue() {
        val saver = DefaultStateSaver()
        saver.save(String::class, "text")
        saver.save(Int::class, 42)

        val params = saver.savedParams

        assertEquals(setOf(String::class, Int::class), params.keys)
        assertEquals("text", params.getValue(String::class)())
        assertEquals(42, params.getValue(Int::class)())
    }

    @Test
    fun savedParamsNoLongerIncludesARemovedType() {
        val saver = DefaultStateSaver()
        saver.save(String::class, "text")

        saver.remove(String::class)

        assertTrue(saver.savedParams.isEmpty())
    }
}
