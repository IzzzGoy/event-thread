package ru.alexey.event.threads.di.koin

import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.Qualifier
import ru.alexey.event.threads.resources.Parameters
import kotlin.reflect.KClass

class KoinDependencyProvider(private val koin: Koin) : DependencyProvider {
    override fun <T : Any> get(clazz: KClass<T>, qualifier: Qualifier?, parameters: Parameters): T =
        koin.get(clazz, qualifier?.let { named(it.value) }) {
            parametersOf(*parameters.values.map { it() }.toTypedArray())
        }

    override fun <T : Any> getOrNull(clazz: KClass<T>, qualifier: Qualifier?, parameters: Parameters): T? =
        koin.getOrNull(clazz, qualifier?.let { named(it.value) }) {
            parametersOf(*parameters.values.map { it() }.toTypedArray())
        }
}

fun Koin.asDependencyProvider(): DependencyProvider = KoinDependencyProvider(this)
