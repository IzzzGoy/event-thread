package ru.alexey.event.threads.resources

import ru.alexey.event.threads.Builder
import kotlin.reflect.KClass

class ResourcesFactory : ResourceProvider {
    val resources = mutableMapOf<KClass<out Any>, (Parameters) -> Resource<out Any>>()


    inline fun <reified T : Any> resolveObserved(parameters: Parameters = emptyMap()): ObservableResource<T>? {
        return resources[T::class]?.let { it(parameters) as? ObservableResource<T> }
    }

    @Builder
    inline fun <reified T : Any> register(noinline block: (Parameters) -> Resource<T>) {
        if (resources.containsKey(T::class)) {
            error("Overriding Resource<${T::class.simpleName}> no allowed")
        }
        resources.put(T::class, block)
    }

    inline fun <reified T : Any> resolve(): Resource<T> {
        return resources[T::class]?.let { it(emptyMap()) as? Resource<T> }
            ?: error("Resource with type <${T::class.simpleName}> not defieend")
    }

    fun <T : Any> resolve(clazz: KClass<T>): Resource<T> {
        return resources[clazz]?.let { it(emptyMap()) as? Resource<T> }
            ?: error("Resource with type <${clazz.simpleName}> not defieend")
    }

    inline fun <reified T : Any> resolve(parameters: Parameters): Resource<T> {
        return resources[T::class]?.let { it(parameters) as? Resource<T> }
            ?: error("Resource with type <${T::class.simpleName}> not defieend")
    }

    fun <T : Any> resolve(clazz: KClass<T>, parameters: Parameters): Resource<T> {
        return resources[clazz]?.let { it(parameters) as? Resource<T> }
            ?: error("Resource with type <${clazz.simpleName}> not defieend")
    }

    inline fun <reified T : Any> get(): T {
        if (!resources.containsKey(T::class)) {
            error("Resource with type <${T::class.simpleName}> not defieend")
        }

        return resources[T::class]?.let { (it(emptyMap()) as? Resource<T>) }?.invoke()!!
    }

    inline fun <reified T : Any, reified A : Any> inject(block: (A) -> T): T = block(get())
    inline fun <reified T : Any, reified A : Any, reified B : Any> inject(block: (A, B) -> T): T =
        block(get(), get())

    inline fun <reified T : Any, reified A : Any, reified B : Any, reified C : Any> inject(block: (A, B, C) -> T): T =
        block(get(), get(), get())

    inline fun <reified T : Any, reified A : Any, reified B : Any, reified C : Any, reified D : Any> inject(
        block: (A, B, C, D) -> T
    ): T =
        block(get(), get(), get(), get())

    override fun <T : Any> resource(clazz: KClass<T>): Resource<T> {
        return resolve(clazz)
    }

    override fun <T : Any> resource(clazz: KClass<T>, parameters: Parameters): Resource<T> {
        return resolve(clazz, parameters)
    }
}

inline fun <reified T : Any> Parameters.resolve(): T {
    return get(T::class)?.let { it() as T } ?: error("Param type <> missing") //error("Param type <${T::class.qualifiedName}> missing")
}

