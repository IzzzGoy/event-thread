package ru.alexey.event.threads.bus

fun interface Interceptor {
    suspend operator fun invoke(event: Event)
}