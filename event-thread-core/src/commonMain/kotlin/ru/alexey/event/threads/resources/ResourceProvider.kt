package ru.alexey.event.threads.resources

import kotlin.reflect.KClass

interface ResourceProvider {
    fun<T: Any> resource(clazz: KClass<T>, name: String = clazz.simpleName.orEmpty()) : Resource<T>
    fun<T: Any> observable(clazz: KClass<T>, name: String = clazz.simpleName.orEmpty()) : ObservableResource<T>
    fun<T: Any> resource(clazz: KClass<T>, name: String = clazz.simpleName.orEmpty(), parameters: Parameters) : Resource<T>
    fun<T: Any> observable(clazz: KClass<T>, name: String = clazz.simpleName.orEmpty(), parameters: Parameters) : ObservableResource<T>
}