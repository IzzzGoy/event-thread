package org.company.sample

import co.touchlab.kermit.Logger
import io.ktor.http.parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.serialization.json.Json
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.cache.cacheJsonResource
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.invoke
import ru.alexey.event.threads.resources.observable
import ru.alexey.event.threads.resources.param
import ru.alexey.event.threads.resources.resolve
import ru.alexey.event.threads.resources.valueResource
import ru.alexey.event.threads.scopeBuilder

data object TestEvent : StrictEvent
data object AnotherEvent : StrictEvent
data class SetLong(val new: Long) : StrictEvent

fun mainScreenScope() = scopeBuilder("Main") {
    config {
        createEventBus {
            watcher {
                Logger.i { it.toString() }
            }
        }
    }

    val longResource by observable {
        flowResource(120L)
    }

    val intResource by observable {
        cacheJsonResource("TEST_NEW", 16, Json)
    }

    val intContainer by datacontainer(intResource()) {
        coroutineScope {
            CoroutineScope(Dispatchers.IO)
        }
    }

    val longContainer by datacontainer(longResource()) {
        transform(otherContainer = intContainer) { other: Int, l -> other.toLong() }
        coroutineScope {
            CoroutineScope(Dispatchers.IO)
        }
    }

    threads {
        eventThread<TestEvent>().then(intContainer) { state, event ->
            12
        }
        eventThread<SetLong>().then(longContainer) { state, event ->
            event.new
        }
    }
}