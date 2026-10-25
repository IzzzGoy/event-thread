package org.company.sample.typed

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import org.company.sample.models.LoadSuggestions
import org.company.sample.models.SuggestionsFailed
import org.company.sample.scopes.SuggestionsScopeDependencyBindings
import org.company.sample.scopes.SuggestionsScopeProbe
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import ru.alexey.event.threads.HttpStatusException
import ru.alexey.event.threads.di.koin.asDependencyProvider
import ru.alexey.event.threads.scopeholder.typed.RemoteData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The generated Suggestions scope end to end: request -> generated Ktor API -> fake server. Only
 * the HttpClient is bound; everything else is the generated defaults. */
class TypedSuggestionsTest {

    private fun probe(failEvery: Int): SuggestionsScopeProbe {
        val provider = koinApplication {
            modules(module { single<HttpClient> { fakeIdeasClient(latencyMs = 10, failEvery = failEvery) } })
        }.koin.asDependencyProvider()
        return SuggestionsScopeProbe(SuggestionsScopeDependencyBindings(provider))
    }

    @Test
    fun loadsIdeasThroughTheGeneratedKtorApi() = runTest {
        val probe = probe(failEvery = 0)
        assertEquals(RemoteData.Idle, probe.suggestions.value)

        probe.send(LoadSuggestions)

        val loaded = assertIs<RemoteData.Success<*>>(probe.await(probe.suggestions) { it is RemoteData.Success })
        assertEquals(3, (loaded.value as List<*>).size)
        probe.close()
    }

    @Test
    fun aServerErrorIsAFailureAndCascades() = runTest {
        val probe = probe(failEvery = 1)

        probe.send(LoadSuggestions)

        val failure = assertIs<RemoteData.Failure<*>>(probe.await(probe.suggestions) { it is RemoteData.Failure })
        assertEquals(503, assertIs<HttpStatusException>(failure.error).status)
        assertEquals(SuggestionsFailed("HTTP 503 from https://ideas.example/api/suggestions?count=3"), probe.awaitEmitted<SuggestionsFailed>())
        assertEquals(1, probe.await(probe.failures) { it == 1 })
        probe.close()
    }
}
