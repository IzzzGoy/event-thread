package ru.alexey.event.threads.scopeholder.typed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeholder.scopeHolder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class Put(val n: Int) : StrictEvent

private data class ListParams(val name: String)

private object ListKey : ScopeKey<ListParams> { override val identity = "List" }
private object SharedKey : ScopeKey<Unit> { override val identity = "Shared" }
private object SourceKey : ScopeKey<Unit> { override val identity = "Source" }

private class Total : DatacontainerTyped<Int>() {
    override val source = flowResource(0)
}

private class PutProcessor(total: Total) : EventHandler.StateProcessor<Put, Int>(total) {
    override suspend fun process(event: Put, state: Int) = state + event.n
}

private class ListScope(params: ListParams, dependencyProvider: DependencyProvider) :
    ScopeTyped<ListParams>(ListKey, params, dependencyProvider = dependencyProvider) {
    override fun ScopeWiring.wire() {
        val total = Total()
        container("total", total)
        on(PutProcessor(total))
    }
}

private class SharedScope(dependencyProvider: DependencyProvider) :
    ScopeTyped<Unit>(SharedKey, Unit, dependencyProvider = dependencyProvider) {
    override fun ScopeWiring.wire() {}
}

class ScopeHolderInstancesTest {

    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5000) { block() } }

    private val work = ListParams("work")
    private val home = ListParams("home")

    private fun holder() = scopeHolder { scopeTyped(ListKey, ::ListScope) }

    @Test
    fun paramsMakeIndependentInstances() = runTest {
        val holder = holder()
        val workList = holder.load(ListKey, work)!!
        val homeList = holder.load(ListKey, home)!!

        assertNotSame(workList, homeList)
        assertSame(workList, holder.find(ListKey, work))
        assertSame(homeList, holder.find(ListKey, home))

        workList + Put(2)
        assertEquals(2, awaitReal { workList.resolveOrThrow<Int>("total").first { it == 2 } })
        assertEquals(0, homeList.resolveOrThrow<Int>("total").value)
        holder.close()
    }

    @Test
    fun findByKeyAloneIsAmbiguousWithSeveralInstances() {
        val holder = holder()
        holder.load(ListKey, work)
        assertNotNull(holder.find(ListKey)) // one instance: unambiguous

        holder.load(ListKey, home)
        val error = assertFailsWith<IllegalStateException> { holder.find(ListKey) }
        assertContains(error.message.orEmpty(), "has 2 live instances")
        holder.close()
    }

    @Test
    fun loadingALiveInstanceReturnsIt() {
        val holder = holder()

        val first = holder.load(ListKey, work)
        val second = holder.load(ListKey, ListParams("work")) // equal params, new object

        assertSame(first, second)
        assertSame(first, holder.findOrLoad(ListKey, work))
        holder.close()
    }

    /** A widget's untyped `scope("List")` must reach the instance a typed mount loaded, not try to
     * load a second one without params. */
    @Test
    fun untypedFindOrLoadReachesTheLiveTypedInstance() {
        val holder = holder()
        val workList = holder.load(ListKey, work)

        assertSame(workList, holder.findOrLoad(ListKey.identity) { emptyMap() })
        holder.close()
    }

    @Test
    fun untypedScopeLoadedTwiceIsOneScope() {
        val holder = scopeHolder { scopeEmbedded("Plain") { } }

        val first = holder.load("Plain")

        assertSame(first, holder.load("Plain"))
        holder.free("Plain")
        assertNull(holder.find("Plain"))
        holder.close()
    }

    @Test
    fun freeingOneInstanceKeepsTheOthers() = runTest {
        val holder = holder()
        holder.load(ListKey, work)
        val homeList = holder.load(ListKey, home)!!

        holder.free(ListKey, work)

        assertNull(holder.find(ListKey, work))
        assertSame(homeList, holder.find(ListKey))
        homeList + Put(1)
        assertEquals(1, awaitReal { homeList.resolveOrThrow<Int>("total").first { it == 1 } })
        holder.close()
    }

    @Test
    fun freeByKeyFreesEveryInstance() {
        val holder = holder()
        holder.load(ListKey, work)
        holder.load(ListKey, home)

        holder.free(ListKey)

        assertNull(holder.find(ListKey, work))
        assertNull(holder.find(ListKey, home))
        holder.close()
    }

    @Test
    fun dependencyLivesWhileAnyInstanceNeedsIt() {
        val holder = scopeHolder {
            scopeTyped(ListKey, ::ListScope)
            scopeTyped(SharedKey, ::SharedScope)
            ListKey dependsOn SharedKey
        }
        holder.load(ListKey, work)
        holder.load(ListKey, home)

        holder.free(ListKey, work)
        assertNotNull(holder.find(SharedKey), "home still depends on Shared")

        holder.free(ListKey, home)
        assertNull(holder.find(SharedKey))
        holder.close()
    }

    @Test
    fun consumedEventsReachEveryInstanceButNotTheEmittersSiblings() = runTest {
        val holder = scopeHolder {
            scopeTyped(ListKey, ::ListScope)
            scopeEmbedded(SourceKey) { }
            Put::class consume ListKey
        }
        val workList = holder.load(ListKey, work)!!
        val homeList = holder.load(ListKey, home)!!
        val source = holder.load(SourceKey)!!

        source + Put(3) // from another scope: routed to both lists
        assertEquals(3, awaitReal { workList.resolveOrThrow<Int>("total").first { it == 3 } })
        assertEquals(3, awaitReal { homeList.resolveOrThrow<Int>("total").first { it == 3 } })

        workList + Put(10) // from a List instance: its sibling must not receive it
        assertEquals(13, awaitReal { workList.resolveOrThrow<Int>("total").first { it == 13 } })
        // give routing a moment to (wrongly) deliver, then check it didn't
        awaitReal { delay(200) }
        assertEquals(3, homeList.resolveOrThrow<Int>("total").value)
        holder.close()
    }

    @Test
    fun concurrentLoadsOfOneInstanceEndWithOneScope() = runTest {
        val holder = holder()

        val loaded = withContext(Dispatchers.Default) {
            (1..50).map { async { holder.findOrLoad(ListKey, work) } }.awaitAll()
        }

        assertTrue(loaded.all { it === loaded.first() })
        assertSame(loaded.first(), holder.find(ListKey))
        holder.close()
    }
}
