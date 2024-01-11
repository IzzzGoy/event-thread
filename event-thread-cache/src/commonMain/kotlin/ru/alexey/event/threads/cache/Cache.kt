package ru.alexey.event.threads.cache

interface Cache<T> {
    @Throws(Exception::class)
    fun load(): T
    @Throws(Exception::class)
    fun write(obj: T)
}