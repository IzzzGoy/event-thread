/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package ru.alexey.event.threads

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.datacontainer.container
import ru.alexey.event.threads.resources.Resource
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.recourses
import kotlin.jvm.JvmInline
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

data object TestEvent : Event

data object WrongEvent : Event

interface Error : Event

@JvmInline
value class IntEvent(val number: Int) : Event


data class Redirect(
    val name: String
) : Event


data class Returned(val s: String, val m: Int)

class TestResource(val i: Returned) : Resource<Returned> {
    override fun invoke(): Returned {
        return i
    }
}


data class Test1(val str: String) : Resource<String> {
    override fun invoke(): String {
        return str
    }
}


data object OnButtonClick: Event
data class Kirill(
    val name: String
)

class LibraryTest {
    @Test
    fun test() = runTest {

        val config = eventsBuilder {

            config {
                createEventBus {
                    watcher {
                        println(it)
                    }
                }
            }
            threads {
                eventThread<TestEvent> {
                    assertSame(TestEvent, it)
                }
            }
        }

        config.eventBus + TestEvent

        delay(1000)

    }

    @Test
    fun test1() = runTest {
        val config = eventsBuilder {

            threads {
                eventThread<TestEvent> {
                    println("!")
                }
                eventThread<WrongEvent> {
                    assertIs<WrongEvent>(it)
                    this + TestEvent
                }
            }
        }

        config.eventBus + WrongEvent
        delay(1000)
    }

    @Test
    fun test2() = runTest(
        timeout = 1.minutes
    ) {

        val config = eventsBuilder {

            config {
                createEventBus {
                    watcher {
                        println("Hello World!")
                    }
                    watcher {
                        println(it)
                    }
                    errorWatcher<Error> {

                    }
                }
            }

            recourses {
                create {
                    flowResource<Long>(
                        get<Double>().toLong()
                    )
                }
                create {
                    flowResource<Double>(51.9)
                }
                create {
                    flowResource(-15)
                }
                create<String> {
                    flowResource("Str")
                }
                create { params ->
                    TestResource(
                        Returned(
                            s = get(),
                            m = params.resolve()
                        )
                    )
                }
            }

            containers {
                container(8) {
                    transform<String> { o: String, i ->
                        o.toInt() + i
                    }
                    transform<Long> { other, i ->
                        i * other.toInt()
                    }
                }
                container("3")

                container {
                    resource<Long>()
                    transform<String> { s: String, l: Long ->
                        s.toLong() * l
                    }
                }
            }

            threads {
                eventThread<TestEvent> {
                    println("!")
                    val resource = resource<Returned> {
                        param { 12 }
                    }
                    println(resource())
                } trigger {
                    Redirect(it.toString())
                }

                eventThread<Redirect> {
                    println("${it} was triggered by Redirect")
                }

                eventThread<WrongEvent> {
                    assertIs<WrongEvent>(it)
                    this + TestEvent
                } bind { value: Long, _ ->
                    value + 1
                }
                eventThread<IntEvent> {

                } bind { value: String, i ->
                    (value.toInt() + i.number).toString()
                } bind { value: Int, i ->
                    value + i.number
                }
            }
        }
        config.eventBus + WrongEvent

        config.eventBus + IntEvent(4)
        launch {
            val dc = config.resolveOrThrow<Long>()
            assertNotNull(dc)
            dc.collect {
                println(it)
            }
        }
    }

    @Test
    fun check() = runTest {
        val config = eventsBuilder {

            config {
                createEventBus {

                }
            }

            recourses {

            }

            containers {
                container(Kirill("Kirill"))
            }

            threads {
                eventThread<OnButtonClick> {
                    println("-----------")
                    println(it)
                    println("-----------")
                }
            }
        }

        config.eventBus + OnButtonClick
    }
}