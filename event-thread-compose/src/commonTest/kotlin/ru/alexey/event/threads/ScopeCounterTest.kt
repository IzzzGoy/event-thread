package ru.alexey.event.threads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ScopeCounter] backs `scope()`'s "free only once the last mounted user unmounts" behavior - see
 * [rememberOrLoadScope]. These pin its reference-counting contract directly, without any Compose
 * runtime involved (it's a plain value class over a `MutableMap`).
 */
class ScopeCounterTest {

    @Test
    fun oneRegisteredUserUnregisteringFreesIt() {
        val counter = ScopeCounter(mutableMapOf())

        counter.register("Todos")

        assertTrue(counter.unregister("Todos"))
    }

    @Test
    fun secondUserKeepsItAliveUntilBothUnregister() {
        val counter = ScopeCounter(mutableMapOf())

        counter.register("Todos")
        counter.register("Todos")

        // Two widgets both mounting scope("Todos") at once - the first to unmount must not
        // free it out from under the second.
        assertFalse(counter.unregister("Todos"))
        assertTrue(counter.unregister("Todos"))
    }

    @Test
    fun unregisteringANameThatWasNeverRegisteredIsASafeNoOp() {
        val counter = ScopeCounter(mutableMapOf())

        // Regression: the previous implementation defaulted an absent name to a count of 1,
        // so this returned true - as if a real last-user-gone transition had happened - telling
        // the caller to free a scope this call had no claim on at all.
        assertFalse(counter.unregister("NeverMounted"))
    }

    @Test
    fun unregisteringTwiceAfterOneRegistrationIsSafe() {
        val counter = ScopeCounter(mutableMapOf())

        counter.register("Todos")

        assertTrue(counter.unregister("Todos"))
        // A second, unbalanced unregister (e.g. a duplicate dispose) must not also report true.
        assertFalse(counter.unregister("Todos"))
    }

    @Test
    fun namesAreCountedIndependently() {
        val counter = ScopeCounter(mutableMapOf())

        counter.register("Todos")
        counter.register("Settings")
        counter.register("Settings")

        // Unregistering "Todos" must not affect "Settings"'s own, independent count.
        assertTrue(counter.unregister("Todos"))
        assertFalse(counter.unregister("Settings"))
        assertTrue(counter.unregister("Settings"))
    }
}
