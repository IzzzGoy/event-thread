package ru.alexey.event.threads.resources

import ru.alexey.event.threads.Builder
import ru.alexey.event.threads.ScopeBuilder
import kotlin.properties.Delegates
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

class ResourcesFactory : ResourceProvider {
    val resources = mutableMapOf<KClass<out Any>, Map<String, (Parameters) -> Resource<out Any>>>()
    val metadata = resources.map { entry ->
        entry.key::simpleName.toString() to entry.value.map {
            it.key
        }
    }

    @Builder
    inline fun <reified T : Any> registerDelegate(noinline block: (Parameters) -> Resource<T>): ReadOnlyProperty<Any?, (Parameters) -> Resource<T>> {
        return ReadOnlyProperty<Any?, (Parameters) -> Resource<T>> { thisRef, property ->
            resources[T::class] =
                resources.getOrPut(T::class, ::emptyMap) + (property.name to block)
            block
        }
    }

    @Builder
    inline fun <reified T : Any> register(
        name: String? = null,
        noinline block: (Parameters) -> Resource<T>
    ) {
        resources[T::class] = resources.getOrPut(T::class, ::emptyMap) + ((name
            ?: T::class.simpleName.orEmpty()) to block)

    }

    inline fun <reified T : Any> resolve(name: String? = null): Resource<T> {
        return resolve(name, T::class)
    }

    fun <T : Any> resolve(name: String? = null, clazz: KClass<T>): Resource<T> {
        return resolve(name, clazz, emptyMap())
    }

    inline fun <reified T : Any> resolve(name: String? = null, parameters: Parameters): Resource<T> {
        return resolve(name, T::class, parameters)
    }

    fun <T : Any> resolve(name: String? = null, clazz: KClass<T>, parameters: Parameters): Resource<T> {
        val factories = resources[clazz] ?: error("Missing factories for type<${clazz}>")
        val factory = factories[name ?: clazz.simpleName.orEmpty()]
            ?: error("Missing factory with name ${name ?: clazz.simpleName.orEmpty()} for type<${clazz}>")
        return factory(parameters) as? Resource<T> ?: error("Registered resource not match with<Resource<${clazz}>>")
    }

    inline fun <reified T : Any> get(name: String? = null): T {
        if (!resources.containsKey(T::class)) {
            error("Resource with type <${T::class.simpleName}> not defieend")
        }
        return resources[T::class]?.get(name ?: T::class.simpleName.orEmpty())?.let { (it(emptyMap()) as? Resource<T>) }?.invoke()!!
    }

    /*inline fun <reified T : Any, reified A : Any> inject(block: (A) -> T): T = block(get())
    inline fun <reified T : Any, reified A : Any, reified B : Any> inject(block: (A, B) -> T): T =
        block(get(), get())

    inline fun <reified T : Any, reified A : Any, reified B : Any, reified C : Any> inject(block: (A, B, C) -> T): T =
        block(get(), get(), get())

    inline fun <reified T : Any, reified A : Any, reified B : Any, reified C : Any, reified D : Any> inject(
        block: (A, B, C, D) -> T
    ): T =
        block(get(), get(), get(), get())*/


    override fun <T : Any> resource(clazz: KClass<T>, name: String): Resource<T> {
        return resolve(name, clazz)
    }

    override fun <T : Any> resource(
        clazz: KClass<T>,
        name: String,
        parameters: Parameters
    ): Resource<T> {
        return resolve(name, clazz, parameters)
    }

    override fun <T : Any> observable(clazz: KClass<T>, name: String): ObservableResource<T> {
        return observable(clazz, name, emptyMap())
    }

    override fun <T : Any> observable(
        clazz: KClass<T>,
        name: String,
        parameters: Parameters
    ): ObservableResource<T> {
        return resolve(name, clazz, parameters) as? ObservableResource<T> ?: error("Resource<$clazz>[$name] is not observable")
    }
}

@Builder
inline fun <reified T : Any> resource(noinline block: (Parameters) -> Resource<T>): ReadOnlyProperty<Any?, (Parameters) -> Resource<T>> {
    return ReadOnlyProperty<Any?, (Parameters) -> Resource<T>> { thisRef, property ->
        block
    }
}

inline fun <reified T : Any> Parameters.resolve(): T {
    return get(T::class)?.let { it() as T }
        ?: error("Param type <> missing") //error("Param type <${T::class.qualifiedName}> missing")
}

inline fun <reified T : Any> MutableMap<KClass<out Any>, () -> Any>.param(noinline block: () -> T) {
    put(T::class, block)
}


inline operator fun <reified T : Any> ((Parameters) -> Resource<T>).invoke(noinline parameters: MutableMap<KClass<out Any>, () -> Any>.() -> Unit = {}): T
        = this(
    buildMap {
        apply(parameters)
        println(this)
    })()
