package ru.alexey.event.threads.utils

/** Small DSL for building a `List<T>` by "calling" each element - `ListBuilder<KClass<*>>().apply
 * { Foo::class(); Bar::class() }()`. Used by [ru.alexey.event.threads.ScopeBuilder]'s callers for
 * list-shaped builder parameters (e.g. `require { }` in `event-thread-compose`'s screen DSL). */
class ListBuilder<T> {
    private val list = mutableListOf<T>()

    operator fun T.invoke() {
        list += this
    }

    /** Returns the accumulated elements, in registration order. */
    operator fun invoke(): List<T> = list
}