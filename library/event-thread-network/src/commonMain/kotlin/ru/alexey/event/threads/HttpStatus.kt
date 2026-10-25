package ru.alexey.event.threads

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** A response outside 2xx - what a generated `<Name>RemoteApiKtor` throws, so a request handler
 * records it as `RemoteData.Failure`. [body] is the response text, for error details. */
class HttpStatusException(val status: Int, val url: String, val body: String) :
    RuntimeException("HTTP $status from $url")

/** Throws [HttpStatusException] unless the response is 2xx - generated Ktor APIs call it on every
 * response (Ktor itself only throws with `expectSuccess = true`). */
suspend fun HttpResponse.successOrThrow(): HttpResponse {
    if (!status.isSuccess()) throw HttpStatusException(status.value, call.request.url.toString(), runCatching { bodyAsText() }.getOrDefault(""))
    return this
}
