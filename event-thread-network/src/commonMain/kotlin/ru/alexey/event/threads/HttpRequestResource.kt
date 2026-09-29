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
import io.ktor.utils.io.core.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import ru.alexey.event.threads.resources.Resource
import ru.alexey.event.threads.resources.valueResource

object HttpRequestResource {
    inline fun <reified T : @Serializable Any> httpGet(
        coroutineScope: CoroutineScope,
        httpClient: HttpClient,
        url: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {}
    ): Resource<ResponseWrapper<T>> {
        return valueResource(
            object : ResponseWrapper<T> {
                override suspend fun unwrap() = coroutineScope.async {
                    httpClient.use {
                        it.get(url, block).body<T>()
                    }
                }.await()
            }
        )
    }

    inline fun <reified T : @Serializable Any, reified R: Any> httpGet(
        coroutineScope: CoroutineScope,
        httpClient: HttpClient,
        resource: R,
        crossinline block: HttpRequestBuilder.() -> Unit = {}
    ): Resource<ResponseWrapper<T>> {
        return valueResource(
            object : ResponseWrapper<T> {
                override suspend fun unwrap() = coroutineScope.async {
                    httpClient.use {
                        it.get(resource, block).body<T>()
                    }
                }.await()
            }
        )
    }

    inline fun <reified T : @Serializable Any> httpPost(
        coroutineScope: CoroutineScope,
        httpClient: HttpClient,
        url: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {}
    ): Resource<ResponseWrapper<T>> {
        return valueResource(
            object : ResponseWrapper<T> {
                override suspend fun unwrap() = coroutineScope.async {
                    httpClient.use {
                        it.post(url, block).body<T>()
                    }
                }.await()
            }
        )
    }

    inline fun <reified T : @Serializable Any, reified R: Any> httpPost(
        coroutineScope: CoroutineScope,
        httpClient: HttpClient,
        resource: R,
        crossinline block: HttpRequestBuilder.() -> Unit = {}
    ): Resource<ResponseWrapper<T>> {
        return valueResource(
            object : ResponseWrapper<T> {
                override suspend fun unwrap() = coroutineScope.async {
                    httpClient.use {
                        it.post(resource, block).body<T>()
                    }
                }.await()
            }
        )
    }

    inline fun <reified T : @Serializable Any> httpDelete(
        coroutineScope: CoroutineScope,
        httpClient: HttpClient,
        url: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {}
    ): Resource<ResponseWrapper<T>> {
        return valueResource(
            object : ResponseWrapper<T> {
                override suspend fun unwrap() = coroutineScope.async {
                    httpClient.use {
                        it.delete(url, block).body<T>()
                    }
                }.await()
            }
        )
    }

    inline fun <reified T : @Serializable Any, reified R: Any> httpDelete(
        coroutineScope: CoroutineScope,
        httpClient: HttpClient,
        resource: R,
        crossinline block: HttpRequestBuilder.() -> Unit = {}
    ): Resource<ResponseWrapper<T>> {
        return valueResource(
            object : ResponseWrapper<T> {
                override suspend fun unwrap() = coroutineScope.async {
                    httpClient.use {
                        it.delete(resource, block).body<T>()
                    }
                }.await()
            }
        )
    }

    inline fun <reified T : @Serializable Any> httpPut(
        coroutineScope: CoroutineScope,
        httpClient: HttpClient,
        url: String,
        crossinline block: HttpRequestBuilder.() -> Unit = {}
    ): Resource<ResponseWrapper<T>> {
        return valueResource(
            object : ResponseWrapper<T> {
                override suspend fun unwrap() = coroutineScope.async {
                    httpClient.use {
                        it.put(url, block).body<T>()
                    }
                }.await()
            }
        )
    }

    inline fun <reified T : @Serializable Any, reified R: Any> httpPut(
        coroutineScope: CoroutineScope,
        httpClient: HttpClient,
        resource: R,
        crossinline block: HttpRequestBuilder.() -> Unit = {}
    ): Resource<ResponseWrapper<T>> {
        return valueResource(
            object : ResponseWrapper<T> {
                override suspend fun unwrap() = coroutineScope.async {
                    httpClient.use {
                        it.put(resource, block).body<T>()
                    }
                }.await()
            }
        )
    }
}