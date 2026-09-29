import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.Privacy
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.datacontainer.datacontainerKey
import ru.alexey.event.threads.datacontainer.DatacontainerKey
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.invoke
import ru.alexey.event.threads.resources.observable
import ru.alexey.event.threads.scopeBuilder
import kotlin.test.Test


data object DummyEvent : StrictEvent
data object DummyEvent2 : StrictEvent

class Test {


    @Test
    fun test() = runTest {
        val resource by observable {
            flowResource(1)
        }
        val key = datacontainerKey(resource())

        val scope = scopeBuilder("test") {

            val intDatacontainer by datacontainer(key) {
                watcher { println(it) }
            }

            threads {
                thread<DummyEvent> {
                    description {
                        "Just a test event"
                    }
                    privacy(Privacy.private)
                }.then(intDatacontainer) { it, _ ->
                    it + 1
                }.then {
                    DummyEvent2
                }.end {
                    println("!!!!!!!!!!!!!!!!!!!!!!!")
                }
                thread<DummyEvent2>().end {
                    println("@@@@@@@@@@@@@@@@@@@@@@@@@")
                }

            }
        }

        println(scope.metadata)
        scope + DummyEvent
    }
}