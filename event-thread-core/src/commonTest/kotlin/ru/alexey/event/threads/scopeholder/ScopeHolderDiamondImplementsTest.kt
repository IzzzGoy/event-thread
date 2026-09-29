package ru.alexey.event.threads.scopeholder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.bus.StrictEvent
import kotlin.test.Test
import kotlin.test.assertEquals

data object DiamondEvent : StrictEvent

class ScopeHolderDiamondImplementsTest {

    // "Y" reaches "Base" through two paths (directly, and via "X"). Before the getAllDeps fix,
    // each path built its own independent ScopeBuilder for "Base", so Y ended up applying Base's
    // registrations twice - this pins that a shared ancestor's registrations are applied exactly
    // once regardless of how many implements-paths reach it.
    @Test
    fun diamondImplementsGraphAppliesSharedAncestorExactlyOnce() = runTest {
        var count = 0
        val done = CompletableDeferred<Unit>()

        val holder = scopeHolder {
            scopeEmbedded("Base") {
                threads {
                    thread<DiamondEvent>().end {
                        count++
                        done.complete(Unit)
                    }
                }
            }
            scopeEmbedded("X") {}
            scopeEmbedded("Y") {}

            "X" implements "Base"
            "Y" implements listOf("Base", "X")
        }

        val scope = holder.findOrLoad("Y")
        scope + DiamondEvent
        done.await()

        assertEquals(1, count)
        holder.free("Y")
    }
}
