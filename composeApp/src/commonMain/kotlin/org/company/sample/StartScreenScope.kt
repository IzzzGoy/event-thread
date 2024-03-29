package org.company.sample

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.cache.cacheJsonResource
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
    resources {
        register {
            cacheJsonResource("int", 1, Json)
        }
    }

    emitters {
        emitter {
            wrapFlow(flowOf(Global))
        }
        /*emitter {
            wrapFlow(flowOf(SetInt(1)))
        }*/
    }

    containers {
        container<Int> {
            bindToResource()
            coroutineScope {
                CoroutineScope(Dispatchers.Main.immediate)
            }
            watcher {
                Logger.d("STATE_START") { it.toString() }
            }
        }
    }

    threads {
        eventThread<SetInt>() bind { state: Int, event ->
            state + event.int
        }
        eventThread<Counter>() bind { state: Int, _ ->
            state + 1
        }
    }
}