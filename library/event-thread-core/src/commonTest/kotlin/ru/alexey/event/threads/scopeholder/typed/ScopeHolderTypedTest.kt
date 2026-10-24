package ru.alexey.event.threads.scopeholder.typed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.dummyProvider
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeholder.scopeHolder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class Bump(val by: Int) : StrictEvent

private data class BoardParams(val name: String)

private object BoardKey : ScopeKey<BoardParams> { override val identity = "Board" }
private object StatsKey : ScopeKey<Unit> { override val identity = "Stats" }
private object MainKey : ScopeKey<Unit> { override val identity = "Main" }

private class Score : DatacontainerTyped<Int>() {
    override val source = flowResource(0)
}

private class BumpProcessor(score: Score) : EventHandler.StateProcessor<Bump, Int>(score) {
    override suspend fun process(event: Bump, state: Int) = state + event.by
}

/** Shape of a generated scope with params: (params, dependencyProvider). */
private class BoardScope(params: BoardParams, dependencyProvider: DependencyProvider) :
    ScopeTyped<BoardParams>(BoardKey, params, dependencyProvider = dependencyProvider) {
    override fun ScopeWiring.wire() {
        val score = Score()
        container("score", score)
        on(BumpProcessor(score))
    }
}

/** Shape of a generated scope without params: (dependencyProvider). */
private class StatsScope(dependencyProvider: DependencyProvider) :
    ScopeTyped<Unit>(StatsKey, Unit, dependencyProvider = dependencyProvider) {
    override fun ScopeWiring.wire() {
        val score = Score()
        container("score", score)
        on(BumpProcessor(score))
    }
}

private abstract class NeedsBinding : EventHandler.SideEffect<Bump>()

private class UnboundScope(dependencyProvider: DependencyProvider) :
    ScopeTyped<Unit>(StatsKey, Unit, dependencyProvider = dependencyProvider) {
    override fun ScopeWiring.wire() {
        handler<Bump, NeedsBinding>()
    }
}

class ScopeHolderTypedTest {

    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5000) { block() } }

    @Test
    fun loadFindAndFreeByTypedKey() = runTest {
        val holder = scopeHolder { scopeTyped(BoardKey, ::BoardScope) }

        val scope = holder.load(BoardKey, BoardParams("work"))

        assertTrue(scope is BoardScope)
        assertEquals(BoardParams("work"), scope.params)
        assertSame(scope, holder.find(BoardKey))
        scope + Bump(3)
        assertEquals(3, awaitReal { scope.resolveOrThrow<Int>("score").first { it == 3 } })

        holder.free(BoardKey)
        assertNull(holder.find(BoardKey))
        holder.close()
    }

    @Test
    fun unitKeyLoadsWithoutParams() {
        val holder = scopeHolder { scopeTyped(StatsKey, ::StatsScope) }

        assertTrue(holder.load(StatsKey) is StatsScope)
        holder.close()
    }

    @Test
    fun providerSetAfterRegistrationIsTheOneUsed() {
        val provider = dummyProvider { }
        val holder = scopeHolder {
            scopeTyped(StatsKey, ::StatsScope)
            dependencyProvider(provider)
        }

        assertSame(provider, holder.load(StatsKey)?.dependencyProvider)
        holder.close()
    }

    @Test
    fun consumedEventsAreRoutedIntoTypedScope() = runTest {
        val holder = scopeHolder {
            scopeTyped(StatsKey, ::StatsScope)
            scopeEmbedded(MainKey) { }
            Bump::class consume StatsKey
        }
        val stats = holder.load(StatsKey)!!
        val main = holder.load(MainKey)!!

        main + Bump(5) // Main has no handler for it; routing delivers it to Stats

        assertEquals(5, awaitReal { stats.resolveOrThrow<Int>("score").first { it == 5 } })
        holder.close()
    }

    @Test
    fun dslScopeDependsOnTypedScope() {
        val holder = scopeHolder {
            scopeTyped(StatsKey, ::StatsScope)
            scopeEmbedded(MainKey) { }
            MainKey dependsOn StatsKey
        }

        holder.load(MainKey)

        assertNotNull(holder.find(StatsKey))
        holder.close()
    }

    @Test
    fun dependsOnAScopeNeedingParamsFailsClearly() {
        val holder = scopeHolder {
            scopeTyped(BoardKey, ::BoardScope)
            scopeEmbedded(MainKey) { }
            MainKey dependsOn BoardKey
        }

        val error = assertFailsWith<IllegalArgumentException> { holder.load(MainKey) }

        assertContains(error.message.orEmpty(), "Scope 'Board' needs BoardParams params, got Unit")
        holder.close()
    }

    @Test
    fun typedScopesInImplementsAreRejectedAtBuild() {
        assertFailsWith<IllegalStateException> {
            scopeHolder {
                scopeTyped(StatsKey, ::StatsScope)
                scopeEmbedded(MainKey) { }
                MainKey implements StatsKey
            }
        }
        assertFailsWith<IllegalStateException> {
            scopeHolder {
                scopeTyped(StatsKey, ::StatsScope)
                scopeEmbedded(MainKey) { }
                StatsKey implements MainKey
            }
        }
    }

    @Test
    fun aKeyRegisteredTwiceIsRejected() {
        assertFailsWith<IllegalStateException> {
            scopeHolder {
                scopeEmbedded(StatsKey) { }
                scopeTyped(StatsKey, ::StatsScope)
            }
        }
        assertFailsWith<IllegalStateException> {
            scopeHolder {
                scopeTyped(StatsKey, ::StatsScope)
                scopeEmbedded(StatsKey) { }
            }
        }
    }

    @Test
    fun factoryBuildingAnotherKeysScopeIsRejected() {
        val holder = scopeHolder { scopeTyped(MainKey) { provider -> StatsScope(provider) } }

        val error = assertFailsWith<IllegalStateException> { holder.load(MainKey) }

        assertContains(error.message.orEmpty(), "Factory for 'Main' built a scope keyed 'Stats'")
        holder.close()
    }

    private object AuditKey : ScopeKey<Unit> { override val identity = "Audit" }

    private class AuditScope(dependencyProvider: DependencyProvider) :
        ScopeTyped<Unit>(AuditKey, Unit, dependencyProvider = dependencyProvider) {
        override fun ScopeWiring.wire() {
            val score = Score()
            container("score", score)
            on(BumpProcessor(score))
        }
    }

    @Test
    fun fragmentDescribesRelationsOfATypedScope() = runTest {
        val mainTopology = ScopeKey.extend(MainKey) { dependsOn(StatsKey) }
        val statsTopology = ScopeKey.extend(StatsKey) { consume<Bump>() }
        val holder = scopeHolder {
            scopeTyped(StatsKey, ::StatsScope)
            scopeEmbedded(MainKey) { }
            include(mainTopology, statsTopology)
        }

        val main = holder.load(MainKey)!!               // dependsOn: loads Stats too
        val stats = assertNotNull(holder.find(StatsKey))
        main + Bump(2)                                   // consume: routed into Stats

        assertEquals(2, awaitReal { stats.resolveOrThrow<Int>("score").first { it == 2 } })
        holder.close()
    }

    @Test
    fun fragmentsMergeWithRelationsDeclaredDirectly() = runTest {
        val holder = scopeHolder {
            scopeTyped(StatsKey, ::StatsScope)
            scopeTyped(AuditKey, ::AuditScope)
            scopeEmbedded(MainKey) { }
            Bump::class consume AuditKey                 // direct
            MainKey dependsOn AuditKey                   // direct
            include(ScopeKey.extend(StatsKey) { consume<Bump>() }, ScopeKey.extend(MainKey) { dependsOn(StatsKey) })
        }

        val main = holder.load(MainKey)!!
        val stats = assertNotNull(holder.find(StatsKey))
        val audit = assertNotNull(holder.find(AuditKey)) // the direct dependsOn survived the fragment's
        main + Bump(4)

        // the direct consume survived the fragment's: both receive it
        assertEquals(4, awaitReal { stats.resolveOrThrow<Int>("score").first { it == 4 } })
        assertEquals(4, awaitReal { audit.resolveOrThrow<Int>("score").first { it == 4 } })
        holder.close()
    }

    @Test
    fun missingBindingFailsTheLoadAndLeavesNothingActive() {
        val holder = scopeHolder { scopeTyped(StatsKey, ::UnboundScope) }

        val error = assertFailsWith<IllegalStateException> { holder.load(StatsKey) }

        assertContains(error.message.orEmpty(), "[NeedsBinding]")
        assertNull(holder.find(StatsKey))
        assertTrue(holder.activeInstances.isEmpty())
        holder.close()
    }
}
