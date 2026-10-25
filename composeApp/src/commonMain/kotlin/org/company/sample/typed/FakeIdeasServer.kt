package org.company.sample.typed

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.company.sample.models.Suggestion
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

private val IDEAS = listOf(
    "Water the plants", "Call mom", "Book a dentist appointment", "Read 20 pages",
    "Back up the laptop", "Plan the weekend", "Fix the bike", "Try a new recipe",
)

/**
 * The "Ideas" service the generated `IdeasRemoteApiKtor` talks to - a Ktor [MockEngine], so the
 * sample runs offline. `GET suggestions?count=N` answers N random ideas after [latencyMs]; every
 * [failEvery]th call answers 503, to show the failure state.
 */
@OptIn(ExperimentalAtomicApi::class)
fun fakeIdeasClient(latencyMs: Long = 700, failEvery: Int = 3): HttpClient {
    val calls = AtomicInt(0)
    val engine = MockEngine { request ->
        delay(latencyMs)
        if (failEvery > 0 && calls.incrementAndFetch() % failEvery == 0) {
            return@MockEngine respond("busy", HttpStatusCode.ServiceUnavailable)
        }
        val count = request.url.parameters["count"]?.toIntOrNull() ?: 3
        val body = Json.encodeToString(IDEAS.shuffled().take(count).map(::Suggestion))
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }
    return HttpClient(engine) {
        install(ContentNegotiation) { json() }
        defaultRequest { url("https://ideas.example/api/") }
    }
}
