package ru.alexey.event.threads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.bus.StrictEvent
import kotlin.test.Test
import kotlin.test.assertEquals

data object MergeEvent : StrictEvent

class EventThreadMergeTest {

    @Test
    fun secondRegistrationForSameEventClassAppendsInsteadOfBeingDropped() = runTest {
        val log = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()

        val parent = scopeBuilder("merge-parent") {
            threads {
                thread<MergeEvent>().end { log += "parent" }
            }
        }(emptyMap())

        val child = scopeBuilder("merge-child", parents = listOf(parent)) {
            threads {
                thread<MergeEvent>().end {
                    log += "child"
                    done.complete(Unit)
                }
            }
        }(emptyMap()).build()

        try {
            child + MergeEvent
            done.await()

            assertEquals(listOf("parent", "child"), log)
        } finally {
            child.close()
        }
    }
}
