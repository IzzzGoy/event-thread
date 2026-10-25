package ru.alexey.event.threads

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpStatusTest {

    private fun client(status: HttpStatusCode, body: String = "") = HttpClient(MockEngine { respond(body, status) })

    @Test
    fun successPassesThrough() = runTest {
        val response = client(HttpStatusCode.OK, "ok").get("https://x.example/a").successOrThrow()
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun nonSuccessThrowsWithStatusUrlAndBody() = runTest {
        val error = assertFailsWith<HttpStatusException> {
            client(HttpStatusCode.NotFound, "no such list").get("https://x.example/lists/1").successOrThrow()
        }
        assertEquals(404, error.status)
        assertEquals("https://x.example/lists/1", error.url)
        assertEquals("no such list", error.body)
        assertEquals("HTTP 404 from https://x.example/lists/1", error.message)
    }
}
