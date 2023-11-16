package ru.alexey.event.threads.resources

import kotlin.reflect.KClass

interface ResourceProvider {
    fun<T: Any> resource(clazz: KClass<T>) : Resource<T>
    fun<T: Any> resource(clazz: KClass<T>, parameters: Parameters) : Resource<T>
}