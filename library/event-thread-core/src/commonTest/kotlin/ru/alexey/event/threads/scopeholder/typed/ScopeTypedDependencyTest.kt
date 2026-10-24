package ru.alexey.event.threads.scopeholder.typed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.bus.Event
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.dummyProvider
import ru.alexey.event.threads.di.register
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.resolve
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private data class Add(val n: Int) : StrictEvent
private data object Check : StrictEvent
private data class Big(val total: Int) : StrictEvent

private class TotalContainer : DatacontainerTyped<Int>() {
    override val source = flowResource(0)
}

private class ReportContainer : DatacontainerTyped<String>() {
    override val source = flowResource("")
}

// --- "generated" bases: Deps bundle what the scope hands each implementation ---

private abstract class AddProcessor(deps: Deps) : EventHandler.StateProcessor<Add, Int>(deps.total) {
    class Deps(val total: TotalContainer)
}

private abstract class CheckCascader(deps: Deps) : EventHandler.EventCascader<Check>() {
    class Deps(val total: ContainerSnapshot<TotalContainer, Int>)
    protected val total = deps.total
    final override val outputs = setOf(Big::class)
}

private abstract class BigProcessor(deps: Deps) : EventHandler.StateProcessor<Big, String>(deps.report) {
    class Deps(val report: ReportContainer)
}

private data class TotalParams(val label: String)

private object TotalKey : ScopeKey<TotalParams> {
    override val identity = "Total"
}

private class TotalScope(dependencyProvider: DependencyProvider, params: TotalParams = TotalParams("main")) :
    ScopeTyped<TotalParams>(TotalKey, params, dependencyProvider = dependencyProvider) {
    override fun ScopeWiring.wire() {
        val total = container("total", TotalContainer())
        val report = container("report", ReportContainer())
        handler<Add, AddProcessor>(AddProcessor.Deps(total))
        handler<Check, CheckCascader>(CheckCascader.Deps(total.snapshot()))
        handler<Big, BigProcessor>(BigProcessor.Deps(report))
    }
}

// --- hand-written implementations ---

private class AddImpl(deps: Deps) : AddProcessor(deps) {
    override suspend fun process(event: Add, state: Int) = state + event.n
}

/** Extracts just "is it big" from the total snapshot. */
private class CheckImpl(deps: Deps) : CheckCascader(deps) {
    private val isBig = total.map { it > 10 }
    override fun cascade(input: Check): List<Event> = if (isBig.value) listOf(Big(total.value)) else emptyList()
}

private class BigImpl(deps: Deps) : BigProcessor(deps) {
    override suspend fun process(event: Big, state: String) = "big: ${event.total}"
}

class ScopeTypedDependencyTest {

    private suspend fun <T> awaitReal(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5000) { block() } }

    private val provider = dummyProvider {
        register<AddProcessor> { AddImpl(it.resolve()) }
        register<CheckCascader> { CheckImpl(it.resolve()) }
        register<BigProcessor> { BigImpl(it.resolve()) }
    }

    @Test
    fun handlersComeFromProviderAndReadSnapshots() = runTest {
        val scope = TotalScope(provider)
        val total = scope.resolveOrThrow<Int>("total")
        val report = scope.resolveOrThrow<String>("report")

        scope + Check // total is 0: not big, cascades nothing
        scope + Add(15)
        awaitReal { total.first { it == 15 } }
        scope + Check

        assertEquals("big: 15", awaitReal { report.first { it.isNotEmpty() } })
        scope.close()
    }

    @Test
    fun everyMissingBindingIsReportedAtOnce() {
        val scope = TotalScope(dummyProvider { register<AddProcessor> { AddImpl(it.resolve()) } })

        val error = assertFailsWith<IllegalStateException> { scope.eventBus }

        assertContains(error.message.orEmpty(), "[CheckCascader, BigProcessor]")
    }

    @Test
    fun eachScopeInstanceGetsItsOwnHandlersAndContainers() = runTest {
        val first = TotalScope(provider)
        val second = TotalScope(provider)

        first + Add(1)
        awaitReal { first.resolveOrThrow<Int>("total").first { it == 1 } }

        assertEquals(0, second.resolveOrThrow<Int>("total").value)
        first.close()
        second.close()
    }

    @Test
    fun keyIdentityAndParamsComeFromTheTypedKey() {
        val scope = TotalScope(provider, TotalParams("work"))

        assertEquals("Total", scope.key)
        assertEquals(TotalKey, scope.scopeKey)
        assertEquals(TotalParams("work"), scope.params)
    }

    @Test
    fun snapshotMapReadsCurrentState() = runTest {
        val total = TotalContainer()
        val doubled = total.snapshot().map { it * 2 }

        total.update { 21 }

        assertEquals(42, awaitReal { total.first { it == 21 }; doubled.value })
        total.close()
    }
}
