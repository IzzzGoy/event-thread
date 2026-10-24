package ru.alexey.event.threads

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.flowResource
import kotlin.test.Test
import kotlin.test.assertEquals

class DatacontainerConcurrencyTest {

    @Test
    fun concurrentUpdatesDoNotLoseWrites() = runTest {
        val source: ObservableResource<Int> = flowResource(0)
        lateinit var container: Datacontainer<Int>

        val scope = scopeBuilder("concurrency-test") {
            val intDatacontainer by datacontainer(source) {}
            container = intDatacontainer
        }(emptyMap()).build()

        try {
            val jobs = (1..100).map { async { container.update { it + 1 } } }
            jobs.awaitAll()

            assertEquals(100, source.value)
        } finally {
            scope.close()
        }
    }
}
