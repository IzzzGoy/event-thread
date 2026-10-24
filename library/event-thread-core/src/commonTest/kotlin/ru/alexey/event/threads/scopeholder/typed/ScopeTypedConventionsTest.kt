package ru.alexey.event.threads.scopeholder.typed

import ru.alexey.event.threads.bus.EventBus
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.Qualifier
import ru.alexey.event.threads.di.dummyProvider
import ru.alexey.event.threads.di.register
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.resolve
import ru.alexey.event.threads.resources.resolveOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The binding conventions generated scopes rely on - what a developer's DependencyProvider
 * bindings receive, and where a scope's bus comes from.
 */
private data object Note : StrictEvent

private data class ShelfParams(val shelf: String)

private object ShelfKey : ScopeKey<ShelfParams> { override val identity = "Shelf" }
private object PlainKey : ScopeKey<Unit> { override val identity = "Plain" }

/** What a binding was resolved with, recorded by the implementation. */
private class ShelfLabel(val params: ShelfParams?) : DatacontainerTyped<String>() {
    override val source = flowResource(params?.shelf ?: "none")
}

private abstract class NoteProcessor(deps: Deps) : EventHandler.StateProcessor<Note, String>(deps.label) {
    class Deps(val label: ShelfLabel)
}

private class NoteImpl(deps: Deps, val params: ShelfParams?) : NoteProcessor(deps) {
    override suspend fun process(event: Note, state: String) = state
}

private class ShelfScope(params: ShelfParams, dependencyProvider: DependencyProvider) :
    ScopeTyped<ShelfParams>(ShelfKey, params, dependencyProvider = dependencyProvider) {
    override fun ScopeWiring.wire() {
        val label = container<String, ShelfLabel>("label")
        handler<Note, NoteProcessor>(NoteProcessor.Deps(label))
    }
}

private class PlainScope(dependencyProvider: DependencyProvider, eventBusFactory: (() -> EventBus)? = null) :
    ScopeTyped<Unit>(PlainKey, Unit, dependencyProvider = dependencyProvider, eventBusFactory = eventBusFactory) {
    override fun ScopeWiring.wire() {
        container<String, ShelfLabel>("label")
    }
}

class ScopeTypedConventionsTest {

    private var lastHandler: NoteImpl? = null

    private val provider = dummyProvider {
        // DummyProvider resolves parameters by type; Koin binds them by position - (deps, params).
        register<ShelfLabel> { ShelfLabel(it.resolveOrNull<ShelfParams>()) }
        register<NoteProcessor> { NoteImpl(it.resolve(), it.resolveOrNull()).also { impl -> lastHandler = impl } }
    }

    @Test
    fun containerBindingReceivesTheScopesParams() {
        val scope = ShelfScope(ShelfParams("kitchen"), provider)

        assertEquals("kitchen", scope.resolveOrThrow<String>("label").value)
        scope.close()
    }

    @Test
    fun handlerBindingReceivesDepsThenParams() {
        val scope = ShelfScope(ShelfParams("garage"), provider)
        scope.eventBus // wire

        assertEquals(ShelfParams("garage"), assertNotNull(lastHandler).params)
        scope.close()
    }

    @Test
    fun unitScopePassesNoParams() {
        val scope = PlainScope(provider)

        assertEquals("none", scope.resolveOrThrow<String>("label").value)
        scope.close()
    }

    @Test
    fun busBoundUnderTheScopeKeyIsUsed() {
        val bus = EventBus.defaultFactory()
        val withBus = dummyProvider {
            register<ShelfLabel> { ShelfLabel(null) }
            register<EventBus>(Qualifier.named("Plain")) { bus }
        }

        assertSame(bus, PlainScope(withBus).eventBus)
    }

    @Test
    fun withoutBindingTheDefaultBusIsUsed() {
        val first = PlainScope(provider)
        val second = PlainScope(provider)

        assertNotSame(first.eventBus, second.eventBus) // a fresh bus per scope instance
        first.close()
        second.close()
    }

    @Test
    fun explicitBusFactoryWinsOverTheBinding() {
        val bound = EventBus.defaultFactory()
        val explicit = EventBus.defaultFactory()
        val withBus = dummyProvider {
            register<ShelfLabel> { ShelfLabel(null) }
            register<EventBus>(Qualifier.named("Plain")) { bound }
        }

        assertSame(explicit, PlainScope(withBus) { explicit }.eventBus)
    }

    @Test
    fun busBoundUnderAnotherKeyIsIgnored() {
        val other = EventBus.defaultFactory()
        val withBus = dummyProvider {
            register<ShelfLabel> { ShelfLabel(null) }
            register<EventBus>(Qualifier.named("Shelf")) { other }
        }

        assertNotSame(other, PlainScope(withBus).eventBus)
        assertNull(withBus.getOrNullUnqualifiedBus())
    }

    private fun DependencyProvider.getOrNullUnqualifiedBus(): EventBus? = getOrNull(EventBus::class, null)
}
