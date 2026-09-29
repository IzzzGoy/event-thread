package ru.alexey.event.threads.utils

/** Generic "produces a [T] via [build]" contract shared by this library's builder classes (e.g.
 * [ru.alexey.event.threads.EventThreadMetadataBuilder]). Not related to the [ru.alexey.event.
 * threads.Builder] `@DslMarker` annotation despite the similar name. */
interface Builder<T> {
    fun build(): T
}