package ru.alexey.event.threads

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import ru.alexey.event.threads.resources.ResourceProvider

object HttpRequestResource {
    inline fun <reified T : @Serializable Any> ResourceProvider.get(
        coroutineScope: CoroutineScope = resource(CoroutineScope::class)(),
        httpClient: HttpClient = resource(HttpClient::class)(),
        url: String,
        crossinline block: HttpRequestBuilder.() -> Unit
    ): ResponseWrapper<T> {
        return object : ResponseWrapper<T> {
            override suspend fun unwrap() = coroutineScope.async {
                httpClient.get(url, block).body<T>()
            }.await()
        }
    }

    inline fun <reified T : @Serializable Any> ResourceProvider.post(
        coroutineScope: CoroutineScope = resource(CoroutineScope::class)(),
        httpClient: HttpClient = resource(HttpClient::class)(),
        url: String,
        crossinline block: HttpRequestBuilder.() -> Unit
    ): ResponseWrapper<T> {
        return object : ResponseWrapper<T> {
            override suspend fun unwrap() = coroutineScope.async {
                httpClient.post(url, block).body<T>()
            }.await()
        }
    }

    inline fun <reified T : @Serializable Any> ResourceProvider.delete(
        coroutineScope: CoroutineScope = resource(CoroutineScope::class)(),
        httpClient: HttpClient = resource(HttpClient::class)(),
        url: String,
        crossinline block: HttpRequestBuilder.() -> Unit
    ): ResponseWrapper<T> {
        return object : ResponseWrapper<T> {
            override suspend fun unwrap() = coroutineScope.async {
                httpClient.delete(url, block).body<T>()
            }.await()
        }
    }

    inline fun <reified T : @Serializable Any> ResourceProvider.put(
        coroutineScope: CoroutineScope = resource(CoroutineScope::class)(),
        httpClient: HttpClient = resource(HttpClient::class)(),
        url: String,
        crossinline block: HttpRequestBuilder.() -> Unit
    ): ResponseWrapper<T> {
        return object : ResponseWrapper<T> {
            override suspend fun unwrap() = coroutineScope.async {
                httpClient.put(url, block).body<T>()
            }.await()
        }
    }
}