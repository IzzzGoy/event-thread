package org.company.sample

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.cache.cacheJsonResource
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.invoke
import ru.alexey.event.threads.resources.observable
import ru.alexey.event.threads.scopeBuilder

data class SetInt(val int: Int) : StrictEvent

fun provideStartScreenScope(name: String) = scopeBuilder(name) {
    config {
        createEventBus {
            coroutineScope {
                CoroutineScope(Dispatchers.Main.immediate)
            }
            watcher {
                Logger.d("START") { it.toString() }
            }
        }
    }

    val intResource by observable {
        cacheJsonResource("int", 1, Json)
    }
    val intDatacontainer by datacontainer(intResource()) {
        coroutineScope {
            CoroutineScope(Dispatchers.Main.immediate)
        }
        watcher {
            Logger.d("STATE_START") { it.toString() }
        }
    }

    emitters {
        emitter {
            wrapFlow(flowOf(Global))
        }
    }

    threads {
        eventThread<SetInt>().then(intDatacontainer) { state: Int, event ->
            state + event.int
        }
        eventThread<Counter>().then(intDatacontainer) { state: Int, _ ->
            state + 1
        }
    }
}