package ru.alexey.event.threads.scopeholder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.alexey.event.threads.bus.StrictEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

data object LifecycleEvent : StrictEvent

class ScopeHolderLifecycleTest {

    @Test
    fun freeClosesTheScopesEventBus() = runTest {
        var count = 0
        val firstSignal = CompletableDeferred<Unit>()

        val holder = scopeHolder {
            scopeEmbedded("s1") {
                threads {
                    thread<LifecycleEvent>().end {
                        count++
                        firstSignal.complete(Unit)
                    }
                }
            }
        }

        val scope = holder.findOrLoad("s1")
        scope + LifecycleEvent
        firstSignal.await()
        assertEquals(1, count)

        holder.free("s1")
        assertNull(holder.find("s1"))

        scope + LifecycleEvent
        withContext(Dispatchers.Default) { delay(150) }

        assertEquals(1, count)
    }
}
