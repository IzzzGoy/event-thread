package ru.alexey.event.threads.scopeholder.typed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.EventBus
import ru.alexey.event.threads.bus.EventBusBuilder
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.resources.flowResource
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private data class Load(val query: String) : StrictEvent
private data class Loaded(val count: Int) : StrictEvent
private data class LoadFailed(val reason: String) : StrictEvent
private data object Unrelated : StrictEvent

private object RequestKey : ScopeKey<Unit> { override val identity = "Requests" }

private class Items : DatacontainerTyped<RemoteData<List<String>>>() {
    override val source = flowResource<RemoteData<List<String>>>(RemoteData.Idle)
}

private class Count : DatacontainerTyped<Int>() {
    override val source = flowResource(0)
}

/** A request whose call is scripted by the test. */
private class Search(
    items: Items,
    override val policy: RequestPolicy = RequestPolicy(),
    private val emitOnSuccess: (Load, List<String>) -> List<Event> = { _, r -> listOf(Loaded(r.size)) },
    private val call: suspend (Load) -> List<String>,
) : EventHandler.Request<Load, List<String>>(items) {
    override val outputs: Set<KClass<out Event>> = setOf(Loaded::class, LoadFailed::class)
    override suspend fun call(event: Load) = call.invoke(event)
    override fun onSuccess(event: Load, result: List<String>) = emitOnSuccess(event, result)
    override fun onFailure(event: Load, error: Throwable) = listOf(LoadFailed(error.message.orEmpty()))
}

private class LoadedCounter(count: Count) : EventHandler.StateProcessor<Loaded, Int>(count) {
    override suspend fun process(event: Loaded, state: Int) = state + 1
}

private class RequestScope(
    val items: Items = Items(),
    val loaded: Count = Count(),
    bus: (() -> EventBus?)? = null,
    request: (Items) -> Search,
) : ScopeTyped<Unit>(RequestKey, Unit, eventBusFactory = bus) {
    private val search = request(items)
    override fun ScopeWiring.wire() {
        container("items", items)
        container("loaded", loaded)
        on(search)
        on(LoadedCounter(loaded))
    }
}

@OptIn(ExperimentalAtomicApi::class)
class RequestTest {

    private suspend fun <T> Datacontainer<T>.awaitValue(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5000) { first(predicate) } }

    @Test
    fun loadingThenSuccessThenCascade() = runTest {
        val scope = RequestScope { items -> Search(items) { listOf(it.query, it.query) } }

        scope + Load("a")

        assertEquals(RemoteData.Success(listOf("a", "a")), scope.items.awaitValue { it is RemoteData.Success })
        assertEquals(1, scope.loaded.awaitValue { it == 1 })   // Loaded(2) cascaded and handled
        scope.close()
    }

    @Test
    fun failureKeepsThePreviousValueAndCascades() = runTest {
        var fail = false
        val scope = RequestScope { items -> Search(items) { if (fail) error("offline") else listOf("x") } }
        val failed = scope.eventBus.output
        scope + Load("1")
        scope.items.awaitValue { it is RemoteData.Success }

        fail = true
        scope + Load("2")
        val failure = assertIs<RemoteData.Failure<List<String>>>(scope.items.awaitValue { it is RemoteData.Failure })
        assertEquals("offline", failure.error.message)
        assertEquals(listOf("x"), failure.previous)
        assertEquals(LoadFailed("offline"), withContext(Dispatchers.Default) { withTimeout(5000) { failed.first { it is LoadFailed } } })
        scope.close()
    }

    @Test
    fun latestCancelsTheRunningRequest() = runTest {
        val calls = AtomicInt(0)
        val scope = RequestScope { items ->
            Search(items, RequestPolicy(concurrency = RequestPolicy.Concurrency.Latest)) {
                calls.incrementAndFetch()
                if (it.query == "slow") awaitCancellation()
                listOf(it.query)
            }
        }
        scope + Load("slow")
        scope.items.awaitValue { it is RemoteData.Loading }
        scope + Load("fast")

        assertEquals(RemoteData.Success(listOf("fast")), scope.items.awaitValue { it is RemoteData.Success })
        assertEquals(2, calls.load())
        scope.close()
    }

    @Test
    fun dropIgnoresTriggersWhileRunning() = runTest {
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInt(0)
        val scope = RequestScope { items ->
            Search(items, RequestPolicy(concurrency = RequestPolicy.Concurrency.Drop)) {
                calls.incrementAndFetch()
                release.await()
                listOf(it.query)
            }
        }
        scope + Load("first")
        scope.items.awaitValue { it is RemoteData.Loading }
        scope + Load("second")
        withContext(Dispatchers.Default) { delay(100) }
        release.complete(Unit)

        assertEquals(RemoteData.Success(listOf("first")), scope.items.awaitValue { it is RemoteData.Success })
        assertEquals(1, calls.load())
        scope.close()
    }

    @Test
    fun queueRunsTriggersInOrder() = runTest {
        val seen = mutableListOf<String>()
        val scope = RequestScope { items ->
            Search(items, RequestPolicy(concurrency = RequestPolicy.Concurrency.Queue), emitOnSuccess = { _, _ -> listOf(Loaded(0)) }) {
                if (it.query == "1") delay(200)
                seen += it.query
                listOf(it.query)
            }
        }
        scope + Load("1")
        scope.items.awaitValue { it is RemoteData.Loading }
        scope + Load("2")

        scope.loaded.awaitValue { it == 2 }
        assertEquals(listOf("1", "2"), seen)
        assertEquals(RemoteData.Success(listOf("2")), scope.items.value)
        scope.close()
    }

    @Test
    fun retriesThenSucceeds() = runTest {
        val calls = AtomicInt(0)
        val scope = RequestScope { items ->
            Search(items, RequestPolicy(retry = 2, retryDelayMs = 10)) {
                if (calls.incrementAndFetch() < 3) error("flaky") else listOf("ok")
            }
        }
        scope + Load("q")
        assertEquals(RemoteData.Success(listOf("ok")), scope.items.awaitValue { it is RemoteData.Success })
        assertEquals(3, calls.load())
        scope.close()
    }

    @Test
    fun timeoutIsAFailure() = runTest {
        val scope = RequestScope { items -> Search(items, RequestPolicy(timeoutMs = 50)) { awaitCancellation() } }
        scope + Load("q")
        val failure = assertIs<RemoteData.Failure<List<String>>>(scope.items.awaitValue { it is RemoteData.Failure })
        assertIs<RequestTimeoutException>(failure.error)
        scope.close()
    }

    /** Sent in a burst, the triggers reach the handler in any order (the bus dispatches events
     * concurrently) - the one sent last still wins. */
    @Test
    fun debounceKeepsOnlyTheLastTrigger() = runTest {
        repeat(20) { debounceOnce() }
    }

    private suspend fun debounceOnce() {
        val calls = mutableListOf<String>()
        val scope = RequestScope { items ->
            Search(items, RequestPolicy(debounceMs = 200)) { calls += it.query; listOf(it.query) }
        }
        scope + Load("a")
        scope + Load("ab")
        scope + Load("abc")
        assertEquals(RemoteData.Success(listOf("abc")), scope.items.awaitValue { it is RemoteData.Success })
        assertEquals(listOf("abc"), calls)
        scope.close()
    }

    @Test
    fun undeclaredOutputIsRejected() = runTest {
        val errors = mutableListOf<Throwable>()
        val bus = { EventBusBuilder().apply { onError { _, e -> errors += e } }.build() }
        val scope = RequestScope(bus = bus) { items -> Search(items, emitOnSuccess = { _, _ -> listOf(Unrelated) }) { listOf("x") } }
        scope + Load("q")
        scope.items.awaitValue { it is RemoteData.Success }
        withContext(Dispatchers.Default) { withTimeout(5000) { while (errors.isEmpty()) delay(10) } }
        assertTrue("Unrelated" in errors.single().message.orEmpty(), errors.toString())
        scope.close()
    }

    @Test
    fun closingTheScopeCancelsARunningRequest() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val scope = RequestScope { items ->
            Search(items) {
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        scope + Load("q")
        scope.items.awaitValue { it is RemoteData.Loading }
        scope.close()
        withContext(Dispatchers.Default) { withTimeout(5000) { cancelled.await() } }
    }

    @Test
    fun executeRunsOneRequestWithoutABus() = runTest {
        val items = Items()
        val search = Search(items) { listOf(it.query) }
        assertEquals(listOf<Event>(Loaded(1)), search.execute(Load("z")))
        assertEquals(RemoteData.Success(listOf("z")), items.awaitValue { it is RemoteData.Success })
    }

    @Test
    fun registeredWithItsOutputs() = runTest {
        val scope = RequestScope { items -> Search(items) { emptyList() } }
        val info = assertNotNull(scope.metadata.eventsMetadata["Load"])
        assertEquals(setOf("Loaded", "LoadFailed"), info.producedTypes)
        scope.close()
    }
}
