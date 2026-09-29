package ru.alexey.event.threads.utils

class ListBuilder<T> {
    private val list = mutableListOf<T>()

    operator fun T.invoke() {
        list += this
    }

    operator fun invoke(): List<T> = list
}