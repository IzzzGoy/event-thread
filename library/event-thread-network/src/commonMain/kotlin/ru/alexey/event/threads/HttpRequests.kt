package ru.alexey.event.threads

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import kotlinx.serialization.Serializable

/**
 * Plain suspend HTTP helpers, meant to be called directly wherever a suspend function already
 * fits - most commonly inside an ordinary `.then(container) { current, event -> ... }`
 * modification step, exactly the same shape `cacheJsonResource`/`SecureResource`'s own `update {
 * }` already use for I/O (see `event-thread-cache`/`event-thread-secure`):
 *
 * ```kotlin
 * thread<RefreshUsers>().then(users) { _, event -> httpClient.getBody("/users") }
 * ```
 *
 * `httpClient` is always caller-owned; nothing here closes it.
 *
 * Replaces the old `ResponseWrapper`/`HttpRequestResource` (`Resource<ResponseWrapper<T>>` +
 * `.unwrap()`). That indirection bought nothing this library's own event/container machinery
 * doesn't already provide, and cost real bugs (an earlier version's `httpClient.use { }` closed
 * the caller's shared client after the first request): a "refresh" is just dispatching an event
 * at a `.then(container) { }` step that calls one of these, same as any other cascade - which
 * means the request shows up in the same watcher trace / `EventGraph` / scenario tests any other
 * cascade does, instead of being invisible to all of them the way a standalone `unwrap()` call was.
 */
suspend inline fun <reified T : @Serializable Any> HttpClient.getBody(
    url: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T = get(url, block).body()

/** [getBody] against a ktor `Resources`-plugin typed [resource] instead of a raw URL. */
suspend inline fun <reified T : @Serializable Any, reified R : Any> HttpClient.getBody(
    resource: R,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T = get(resource, block).body()

suspend inline fun <reified T : @Serializable Any> HttpClient.postBody(
    url: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T = post(url, block).body()

/** [postBody] against a ktor `Resources`-plugin typed [resource] instead of a raw URL. */
suspend inline fun <reified T : @Serializable Any, reified R : Any> HttpClient.postBody(
    resource: R,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T = post(resource, block).body()

suspend inline fun <reified T : @Serializable Any> HttpClient.putBody(
    url: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T = put(url, block).body()

/** [putBody] against a ktor `Resources`-plugin typed [resource] instead of a raw URL. */
suspend inline fun <reified T : @Serializable Any, reified R : Any> HttpClient.putBody(
    resource: R,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T = put(resource, block).body()

suspend inline fun <reified T : @Serializable Any> HttpClient.deleteBody(
    url: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T = delete(url, block).body()

/** [deleteBody] against a ktor `Resources`-plugin typed [resource] instead of a raw URL. */
suspend inline fun <reified T : @Serializable Any, reified R : Any> HttpClient.deleteBody(
    resource: R,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T = delete(resource, block).body()
