package ru.alexey.event.threads.resources

import ru.alexey.event.threads.ScopeEventsThreadBuilder
import kotlin.reflect.KClass

class RecoursesBuilder {
    val recourses = mutableMapOf<KClass<out Any>, () -> Resource<out Any>>()


    inline fun<reified T: Any> resolveObserved(): ObservableResource<T>? {
        return recourses[T::class]?.let { it() as? ObservableResource<T>  }
    }

    inline fun<reified T: Any> create(noinline block: () -> Resource<T>) {
        recourses.put(T::class, block)
    }
}

inline fun ScopeEventsThreadBuilder.recourses(block: RecoursesBuilder.() -> Unit) {
    resources.apply(block)
}