package ru.alexey.event.threads.cache

interface Cache<T> {
    fun load(): T
    fun write(obj: T)
}