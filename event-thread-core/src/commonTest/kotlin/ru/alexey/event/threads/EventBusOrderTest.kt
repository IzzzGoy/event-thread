package ru.alexey.event.threads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.bus.StrictEvent
import kotlin.test.Test
import kotlin.test.assertEquals

data object OrderEvent : StrictEvent

class EventBusOrderTest {

    @Test
    fun actionsWithinAThreadRunInRegistrationOrder() = runTest {
        val log = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()

        val scope = scopeBuilder("order-test") {
            threads {
                thread<OrderEvent> {}
                    .end {
                        delay(100)
                        log += "first"
                    }
                    .end {
                        log += "second"
                        done.complete(Unit)
                    }
            }
        }(emptyMap()).build()

        try {
            scope + OrderEvent
            done.await()

            assertEquals(listOf("first", "second"), log)
        } finally {
            scope.close()
        }
    }
}
