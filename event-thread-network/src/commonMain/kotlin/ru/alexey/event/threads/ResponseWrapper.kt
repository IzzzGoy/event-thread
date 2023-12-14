package ru.alexey.event.threads

import kotlinx.serialization.Serializable

interface ResponseWrapper<T: @Serializable Any> {
    suspend fun unwrap() : T
}