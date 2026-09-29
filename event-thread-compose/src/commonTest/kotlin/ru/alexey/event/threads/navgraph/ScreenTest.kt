package ru.alexey.event.threads.navgraph

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * [Screen.checkParams] is what actually guards a navigation push at runtime (called from
 * `navGraph`'s `PUSH` cascade before the screen is added to the stack - see `NavGraphScope.kt`);
 * [ScreenBuilder]'s `key()` backs `PopToScreen`'s target matching. Neither needs a live
 * Composition to test - `content { }` here is stored, never invoked.
 */
class ScreenTest {

    private fun screen(vararg required: KClass<out Any>) =
        ScreenBuilder().apply {
            required.forEach { require(it) }
            content { }
        }()

    @Test
    fun checkParamsPassesWithExactlyTheRequiredTypes() {
        screen(String::class).checkParams(mapOf(String::class to { "value" }))
    }

    @Test
    fun checkParamsPassesWithNoRequiredParamsAndNoneSupplied() {
        screen().checkParams(emptyMap())
    }

    @Test
    fun checkParamsThrowsWhenARequiredParamIsMissing() {
        assertFailsWith<IllegalArgumentException> {
            screen(String::class).checkParams(emptyMap())
        }
    }

    @Test
    fun checkParamsThrowsWhenAnUnrelatedParamReplacesTheRequiredOne() {
        assertFailsWith<IllegalArgumentException> {
            screen(String::class).checkParams(mapOf(Int::class to { 1 }))
        }
    }

    @Test
    fun checkParamsThrowsWhenAnExtraParamIsSuppliedAlongsideARequiredOne() {
        assertFailsWith<IllegalArgumentException> {
            screen(String::class).checkParams(mapOf(String::class to { "value" }, Int::class to { 1 }))
        }
    }

    @Test
    fun defaultKeysAreRandomAndDistinctPerScreen() {
        assertNotEquals(screen().key, screen().key)
    }

    @Test
    fun keyCanBeSetExplicitly() {
        val screen = ScreenBuilder().apply {
            key("home")
            content { }
        }()
        assertEquals("home", screen.key)
    }
}
