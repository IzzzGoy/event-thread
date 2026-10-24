package ru.alexey.event.threads

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.datacontainer.Datacontainer
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.scopeHolder
import ru.alexey.event.threads.scopeholder.typed.DatacontainerTyped
import ru.alexey.event.threads.scopeholder.typed.EventHandler
import ru.alexey.event.threads.scopeholder.typed.ScopeKey
import ru.alexey.event.threads.scopeholder.typed.ScopeTyped
import ru.alexey.event.threads.scopeholder.typed.ScopeWiring
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class Bump(val by: Int) : StrictEvent
private data class CounterParams(val name: String)

private object CounterKey : ScopeKey<CounterParams> { override val identity = "Counter" }
private object TotalKey : ScopeKey<Unit> { override val identity = "Total" }

private class Score : DatacontainerTyped<Int>() {
    override val source = flowResource(0)
}

private class BumpProcessor(score: Score) : EventHandler.StateProcessor<Bump, Int>(score) {
    override suspend fun process(event: Bump, state: Int) = state + event.by
}

/** Shape of a generated scope: typed container property, `send` per handled event. */
private class CounterScope(params: CounterParams, provider: DependencyProvider) :
    ScopeTyped<CounterParams>(CounterKey, params, dependencyProvider = provider) {
    val score: Score get() = typedContainer("score")
    fun send(event: Bump) = plus(event)
    override fun ScopeWiring.wire() {
        on(BumpProcessor(container("score", Score())))
    }
}

private class TotalScope(provider: DependencyProvider) : ScopeTyped<Unit>(TotalKey, Unit, dependencyProvider = provider) {
    override fun ScopeWiring.wire() {
        container("score", Score())
    }
}

private object NoopApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

private object Owner : LifecycleOwner {
    override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this)
}

@OptIn(ExperimentalCoroutinesApi::class)
class TypedScopeComposeTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun holder() = scopeHolder {
        scopeTyped(CounterKey, ::CounterScope)
        scopeTyped(TotalKey, ::TotalScope)
    }

    /** Composes [content] under the library's root providers; returns the composition to dispose. */
    private fun compose(holder: ScopeHolder, content: @androidx.compose.runtime.Composable () -> Unit): Composition {
        val composition = Composition(NoopApplier, Recomposer(Dispatchers.Unconfined))
        composition.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides Owner) {
                ScopeHolder({ holder }) { content() }
            }
        }
        return composition
    }

    private suspend fun <T> Datacontainer<T>.awaitValue(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(5000) { first(predicate) } }

    @Test
    fun contentRunsOnTheTypedScopeInstance() = runTest {
        val holder = holder()
        var mounted: CounterScope? = null
        val composition = compose(holder) {
            typedScope(CounterKey, CounterParams("a"), cast = { it as? CounterScope }) { mounted = this }
        }
        val scope = mounted!!
        assertEquals(CounterParams("a"), scope.params)
        assertSame(scope, holder.find(CounterKey.identity, CounterParams("a")))

        scope.send(Bump(3))
        assertEquals(3, scope.score.awaitValue { it == 3 })

        composition.dispose()
        // Unmounted: the instance is freed.
        assertEquals(null, holder.find(CounterKey.identity, CounterParams("a")))
    }

    @Test
    fun scopesWithoutParamsAndCurrentTypedScope() = runTest {
        var inner: TotalScope? = null
        var outer: TotalScope? = null
        compose(holder()) {
            typedScope(TotalKey, Unit, cast = { it as? TotalScope }) {
                outer = this
                inner = currentTypedScope { it as? TotalScope }
            }
        }
        assertTrue(outer != null)
        assertSame(outer, inner)
    }

    @Test
    fun wrongScopeTypeFailsWithAHint() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            compose(holder()) { typedScope(TotalKey, Unit, cast = { it as? CounterScope as? ScopeTyped<Unit> }) { } }
        }
        assertTrue("register it with its generated register" in error.message.orEmpty(), error.message)
    }
}
