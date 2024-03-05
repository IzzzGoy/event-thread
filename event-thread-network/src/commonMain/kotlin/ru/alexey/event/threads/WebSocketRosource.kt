package ru.alexey.event.threads

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ru.alexey.event.threads.resources.ObservableResource

inline fun <reified T: @Serializable Any> webSocketResource(
    host: String,
    port: Int,
    path: String,
    httpClient: HttpClient,
    coroutineScope: CoroutineScope,
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