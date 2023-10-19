package ru.alexey.event.threads.resources

import ru.alexey.event.threads.ScopeEventsThreadBuilder
import kotlin.reflect.KClass

class RecoursesBuilder {
    val recourses = mutableMapOf<KClass<out Any>, (Parameters) -> Resource<out Any>>()


    inline fun <reified T : Any> resolveObserved(parameters: Parameters = emptyMap()): ObservableResource<T>? {
        return recourses[T::class]?.let { it(parameters) as? ObservableResource<T> }
    }

    inline fun <reified T : Any> create(noinline block: (Parameters) -> Resource<T>) {
        if (recourses.containsKey(T::class)) {
            error("Overriding Resource<${T::class.simpleName}> no allowed")
        }
        recourses.put(T::class, block)
    }

    inline fun <reified T : Any> Parameters.resolve(): T {
        return get(T::class)?.let { it() as T } ?: error("Param type <${T::class.simpleName}> missing")
    }

            inline fun <reified T : Any> resolve(): Resource<T> {
        return recourses[T::class]?.let { it(emptyMap()) as? Resource<T> }
            ?: error("Resource with type <${T::class.simpleName}> not defieend")
    }
    inline fun <reified T : Any> resolve(parameters: Parameters): Resource<T> {
        return recourses[T::class]?.let { it(parameters) as? Resource<T> }
            ?: error("Resource with type <${T::class.simpleName}> not defieend")
    }

    inline fun <reified T : Any> get(): T {
        if (!recourses.containsKey(T::class)) {
            error("Resource with type <${T::class.simpleName}> not defieend")
        }
        return recourses[T::class]?.let { (it(emptyMap()) as? Resource<T>) }?.invoke()!!
    }

    inline fun <reified T : Any, reified A : Any> inject(block: (A) -> T): T = block(get())
    inline fun <reified T : Any, reified A : Any, reified B : Any> inject(block: (A, B) -> T): T = block(get(), get())
    inline fun <reified T : Any, reified A : Any, reified B : Any, reified C : Any> inject(block: (A, B, C) -> T): T =
        block(get(), get(), get())

    inline fun <reified T : Any, reified A : Any, reified B : Any, reified C : Any, reified D : Any> inject(block: (A, B, C, D) -> T): T =
        block(get(), get(), get(), get())
}

inline fun ScopeEventsThreadBuilder.recourses(block: RecoursesBuilder.() -> Unit) {
    resources.apply(block)
}