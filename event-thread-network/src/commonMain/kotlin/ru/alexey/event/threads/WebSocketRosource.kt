package ru.alexey.event.threads

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ru.alexey.event.threads.resources.ObservableResource
import ru.alexey.event.threads.resources.ResourceProvider

inline fun <reified T: @Serializable Any> ResourceProvider.webSocketResource(
    host: String,
    port: Int,
    path: String,
    httpClient: HttpClient = resource(HttpClient::class)(),
    coroutineScope: CoroutineScope = resource(CoroutineScope::class)(),
    initial: T
): ObservableResource<T> {
    val source = MutableStateFlow(initial)
    var sender: DefaultClientWebSocketSession? = null
    coroutineScope.launch {
        httpClient.webSocket(method = HttpMethod.Get, host, port, path) {
            sender = this
            while(true) {
                source.emit(receiveDeserialized())
            }
        }
    }
    return object : ObservableResource<T>, StateFlow<T> by source {
        override suspend fun update(block: (T) -> T) {
            sender?.sendSerialized(
                block(value)
            )
        }
    }
}