package ru.alexey.event.threads

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class Greeting(val text: String)

/**
 * Regression coverage for the "shared HttpClient closed after the first request" bug: an earlier
 * version (`HttpRequestResource`/`ResponseWrapper`, since removed) wrapped every request in
 * `httpClient.use { }`, which closes the client once the request completes - since a client is
 * normally shared and reused across many calls, that broke every call after the first one made
 * through it. [MockEngine] stands in for a real network - only the client lifecycle is under
 * test.
 */
class HttpRequestsTest {

    private fun mockClient(respond: () -> String) = HttpClient(MockEngine) {
        install(ContentNegotiation) { json() }
        engine {
            addHandler { respond(respond(), headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        }
    }

    @Test
    fun getBodyCanBeCalledMoreThanOnceOnTheSameClient() = runTest {
        var callCount = 0
        val client = mockClient {
            callCount++
            """{"text":"call $callCount"}"""
        }

        assertEquals(Greeting("call 1"), client.getBody("https://example.test/greeting"))
        assertEquals(Greeting("call 2"), client.getBody("https://example.test/greeting"))
        assertEquals(2, callCount)
    }

    @Test
    fun postPutDeleteAlsoDoNotCloseTheSharedClient() = runTest {
        var callCount = 0
        val client = mockClient {
            callCount++
            """{"text":"call $callCount"}"""
        }

        assertEquals(Greeting("call 1"), client.postBody("https://example.test/x"))
        assertEquals(Greeting("call 2"), client.putBody("https://example.test/x"))
        assertEquals(Greeting("call 3"), client.deleteBody("https://example.test/x"))
    }
}
