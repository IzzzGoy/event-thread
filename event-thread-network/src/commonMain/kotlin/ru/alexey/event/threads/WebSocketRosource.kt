package ru.alexey.event.threads

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ru.alexey.event.threads.resources.ObservableResource
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Default delay between reconnect attempts - see [webSocketResource]'s KDoc. */
const val DEFAULT_WEBSOCKET_RECONNECT_DELAY_MS = 3000L

/**
 * An [ObservableResource] backed by a live WebSocket connection: incoming (deserialized) frames
 * update the resource's value, [ObservableResource.update] sends one out.
 *
 * Reconnects automatically, after [reconnectDelayMs], whenever the connection drops or fails to
 * establish. An earlier version ran the session exactly once inside a plain `coroutineScope.
 * launch { }` with no surrounding loop: any failure at all - a dropped connection, a
 * deserialization error on one bad frame, the server closing the socket - permanently killed the
 * resource, since nothing ever tried again. [onError] is invoked on each failed attempt (reported
 * via a diagnostic `println` by default, the same "always visible, never silently swallowed"
 * convention `EventBus`'s own error handling uses) - the reconnect loop keeps retrying regardless
 * of what [onError] does with the failure.
 *
 * [update] is a no-op (not an error) while no connection is currently established - there is no
 * outgoing message queue, so a call made between connecting attempts is simply dropped, not
 * buffered for the next successful connection.
 */
@OptIn(ExperimentalAtomicApi::class)
inline fun <reified T : @Serializable Any> webSocketResource(
    host: String,
    port: Int,
    path: String,
    httpClient: HttpClient,
    coroutineScope: CoroutineScope,
    initial: T,
    reconnectDelayMs: Long = DEFAULT_WEBSOCKET_RECONNECT_DELAY_MS,
    noinline onError: (Throwable) -> Unit = {
        println("webSocketResource: connection to $host:$port$path failed, retrying in ${reconnectDelayMs}ms: $it")
    },
): ObservableResource<T> {
    val source = MutableStateFlow(initial)
    val senderRef = AtomicReference<DefaultClientWebSocketSession?>(null)

    coroutineScope.launch {
        while (isActive) {
            try {
                httpClient.webSocket(method = HttpMethod.Get, host, port, path) {
                    senderRef.store(this)
                    try {
                        while (true) {
                            source.emit(receiveDeserialized())
                        }
                    } finally {
                        // Cleared even on a clean/expected close, not just a failure - update()
                        // must stop trying to send on this now-dead session either way.
                        senderRef.store(null)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                onError(t)
            }
            if (isActive) delay(reconnectDelayMs)
        }
    }

    return object : ObservableResource<T>, StateFlow<T> by source {
        override suspend fun update(block: (T) -> T) {
            senderRef.load()?.sendSerialized(block(value))
        }
    }
}
