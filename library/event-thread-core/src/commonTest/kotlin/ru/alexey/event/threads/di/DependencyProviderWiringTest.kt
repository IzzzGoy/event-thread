package ru.alexey.event.threads.di

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.bus.StrictEvent
import ru.alexey.event.threads.resources.param
import ru.alexey.event.threads.resources.resolveOrDefault
import ru.alexey.event.threads.scopeholder.scopeHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

data class Greeting(val text: String)
data class RequestGreeting(val marker: String) : StrictEvent

class DependencyProviderWiringTest {

    @Test
    fun scopeBodyResolvesTheProviderConfiguredOnTheHolder() = runTest {
        val received = mutableListOf<String>()
        val gotIt = CompletableDeferred<Unit>()

        val holder = scopeHolder {
            dependencyProvider(dummyProvider {
                register<Greeting> { Greeting("hello") }
            })

            scopeEmbedded("Greeter") {
                threads {
                    thread<RequestGreeting>().end {
                        received += dependencyProvider.get<Greeting>().text
                        gotIt.complete(Unit)
                    }
                }
            }
        }

        try {
            val scope = holder.findOrLoad("Greeter")
            scope + RequestGreeting("go")
            gotIt.await()

            assertEquals(listOf("hello"), received)
        } finally {
            holder.close()
        }
    }

    @Test
    fun qualifiedAndParameterizedLookupsAreDistinct() {
        val provider = dummyProvider {
            register<Greeting> { Greeting("default") }
            register<Greeting>(Qualifier.named("formal")) { params ->
                Greeting("Good day, ${params.resolveOrDefault("stranger")}")
            }
        }

        assertEquals("default", provider.get<Greeting>().text)
        assertEquals(
            "Good day, Alexey",
            provider.get<Greeting>(Qualifier.named("formal")) { param { "Alexey" } }.text
        )
    }

    @Test
    fun unconfiguredProviderThrowsOnGetAndReturnsNullOnGetOrNull() {
        val provider = DummyProvider()

        assertNull(provider.getOrNull<Greeting>())
        assertFailsWith<IllegalStateException> { provider.get<Greeting>() }
    }
}
