package ru.alexey.event.threads.di.koin

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import ru.alexey.event.threads.di.Qualifier
import ru.alexey.event.threads.di.get
import ru.alexey.event.threads.di.getOrNull
import ru.alexey.event.threads.resources.param
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Greeter(val text: String)

class KoinDependencyProviderTest {

    @Test
    fun resolvesAPlainSingleByItsDeclaredType() {
        // Regression coverage for the `single { Foo() }` vs `single<Foo> { Foo() }` pitfall: Koin
        // infers the registered type from the lambda's static return type, which is only the
        // interface/class our adapter is asked for (`Greeter::class` here) when it's spelled out
        // explicitly - the same trap that broke `single { Json }` in the sample app (it silently
        // registered under `Json.Default::class` instead of `Json::class`).
        val koin = koinApplication {
            modules(module { single<Greeter> { Greeter("hello") } })
        }.koin
        val provider = koin.asDependencyProvider()

        assertEquals("hello", provider.get<Greeter>().text)
    }

    @Test
    fun qualifierSelectsBetweenTwoDefinitionsOfTheSameType() {
        val koin = koinApplication {
            modules(module {
                single<Greeter> { Greeter("default") }
                single<Greeter>(org.koin.core.qualifier.named("formal")) { Greeter("Good day") }
            })
        }.koin
        val provider = koin.asDependencyProvider()

        assertEquals("default", provider.get<Greeter>().text)
        assertEquals("Good day", provider.get<Greeter>(Qualifier.named("formal")).text)
    }

    @Test
    fun parametersFlowThroughToTheDefinition() {
        val koin = koinApplication {
            modules(module { single<Greeter> { (name: String) -> Greeter("Hello, $name") } })
        }.koin
        val provider = koin.asDependencyProvider()

        assertEquals("Hello, Alexey", provider.get<Greeter> { param { "Alexey" } }.text)
    }

    @Test
    fun getOrNullReturnsNullForAnUnregisteredType() {
        val koin = koinApplication { modules(module {}) }.koin
        val provider = koin.asDependencyProvider()

        assertNull(provider.getOrNull<Greeter>())
    }
}
