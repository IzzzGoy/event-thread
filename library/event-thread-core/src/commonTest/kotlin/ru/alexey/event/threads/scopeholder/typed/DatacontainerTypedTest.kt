package ru.alexey.event.threads.scopeholder.typed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.FlowResource
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Counter(override val source: FlowResource<Int> = flowResource(0)) : DatacontainerTyped<Int>()

private class Label(initial: String) : DatacontainerTyped<String>() {
    override val source = flowResource(initial)
}

/** Transform targets are subclass fields - unset while the base constructor runs. */
private class Summary(
    private val counter: Datacontainer<Int>,
    private val label: Label,
) : DatacontainerTyped<String>() {
    override val source = flowResource("")
    val seen = mutableListOf<String>()

    override val transforms = listOf(
        transform(counter) { count, _ -> "${label.value}=$count" },
        transform(label) { text, _ -> "$text=${counter.value}" },
    )

    override fun onEach(value: String) {
        seen += value
    }
}

private class Recording : DatacontainerTyped<Int>() {
    override val source = flowResource(0)
    val seen = MutableStateFlow(emptyList<Int>())
    override fun onEach(value: Int) {
        seen.update { it + value }
    }
}

class DatacontainerTypedTest {

    // The pipeline runs on real Dispatchers.Default coroutines, not runTest's virtual clock.
    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5000) { block() } }

    @Test
    fun initialValueComesFromSource() {
        val counter = Counter(flowResource(5))
        assertEquals(5, counter.value)
        counter.close()
    }

    @Test
    fun updateWritesThroughToSource() = runTest {
        val source = flowResource(0)
        val counter = Counter(source)

        counter.update { it + 2 }

        assertEquals(2, source.value)
        assertEquals(2, awaitReal { counter.first { it == 2 } })
        counter.close()
    }

    @Test
    fun writeIsReadableImmediatelyWithoutTransforms() = runTest {
        // A pipeline on a dispatcher that hasn't run yet: value must not wait for it.
        val paused = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val counter = object : DatacontainerTyped<Int>(CoroutineScope(paused)) {
            override val source = flowResource(0)
        }

        counter.update { 7 }

        assertEquals(7, counter.value)
        counter.close()
    }

    @Test
    fun transformsReadSubclassFieldsAndFollowEitherSource() = runTest {
        val counter = Counter()
        val label = Label("count")
        val summary = Summary(counter, label)

        assertEquals("count=0", awaitReal { summary.first { it == "count=0" } })
        counter.update { 3 }
        assertEquals("count=3", awaitReal { summary.first { it == "count=3" } })
        label.update { "total" }
        assertEquals("total=3", awaitReal { summary.first { it == "total=3" } })

        listOf(summary, label, counter).forEach { it.close() }
    }

    @Test
    fun transformsAcceptDslContainers() = runTest {
        val scope = scopeBuilder("typed-interop") {
            val count by datacontainer(flowResource(0)) { }
            count
            threads { }
        }(emptyMap()).build()
        val dslCounter = scope.resolveOrThrow<Int>()
        val summary = Summary(dslCounter, Label("dsl"))

        dslCounter.update { 7 }

        assertEquals("dsl=7", awaitReal { summary.first { it == "dsl=7" } })
        summary.close()
        scope.close()
    }

    @Test
    fun onEachSeesUpdatesWithoutAnySubscriber() = runTest {
        val recording = Recording()

        // No collector, no `value` read - update() alone has to start the pipeline.
        recording.update { 1 }
        recording.update { 2 }

        val seen = awaitReal { recording.seen.first { it.lastOrNull() == 2 } }
        // StateFlow conflates, so intermediate values may be skipped - but never reordered.
        assertEquals(seen.sorted(), seen)
        recording.close()
    }

    @Test
    fun concurrentUpdatesDoNotLoseWrites() = runTest {
        val source = flowResource(0)
        val counter = Counter(source)

        withContext(Dispatchers.Default) {
            (1..1000).map { async { counter.update { it + 1 } } }.awaitAll()
        }

        assertEquals(1000, source.value)
        assertEquals(1000, awaitReal { counter.first { it == 1000 } })
        counter.close()
    }

    @Test
    fun closeStopsOnlyThisContainer() {
        val parentJob = Job()
        val parent = CoroutineScope(Dispatchers.Default + parentJob)
        val counter = object : DatacontainerTyped<Int>(parent) {
            override val source = flowResource(0)
        }
        counter.value // start the pipeline on the parent's child scope

        counter.close()

        assertTrue(parent.isActive, "closing a container must not cancel the scope it was given")
        parentJob.cancel()
    }
}
