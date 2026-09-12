package ru.alexey.event.threads.scopeholder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.alexey.event.threads.bus.StrictEvent
import kotlin.test.Test
import kotlin.test.assertEquals

data object DiamondEmitterEvent : StrictEvent

class ScopeHolderDiamondEmittersTest {

    // Same diamond shape and same root cause as ScopeHolderDiamondImplementsTest, but through
    // `emitters { }` instead of `threads { }` - both go through ScopeBuilder.apply(), fixed
    // together in getAllDeps(), but only the threads path had a regression test.
    @Test
    fun diamondImplementsGraphAppliesSharedAncestorEmitterExactlyOnce() = runTest {
        var count = 0
        val done = CompletableDeferred<Unit>()

        val holder = scopeHolder {
            scopeEmbedded("Base") {
                threads {
                    thread<DiamondEmitterEvent>().end {
                        count++
                        done.complete(Unit)
                    }
                }
                emitters {
                    emitter {
                        wrapFlow(flowOf(DiamondEmitterEvent))
                    }
                }
            }
            scopeEmbedded("X") {}
            scopeEmbedded("Y") {}

            "X" implements "Base"
            "Y" implements listOf("Base", "X")
        }

        holder.findOrLoad("Y")
        done.await()

        // A duplicate emitter (the bug) would fire the second copy right away too - give it a
        // moment to show up before asserting.
        withContext(Dispatchers.Default) { delay(150) }

        assertEquals(1, count)
        holder.free("Y")
    }
}
