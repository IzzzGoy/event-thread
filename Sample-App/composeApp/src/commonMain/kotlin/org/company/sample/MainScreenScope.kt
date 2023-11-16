package org.company.sample

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.cache.cacheJSONResource
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeBuilder

data object TestEvent : StrictEvent
data object AnotherEvent : StrictEvent
data class SetLong(val new: Long) : StrictEvent

fun mainScreenScope() = scopeBuilder {
    name { "Main" }
    config {
        createEventBus {
            watcher {
                Logger.i { it.toString() }
            }
        }
    }

    resources {
        register {
            cacheJSONResource("TEST", CoroutineScope(Dispatchers.IO), 16)
        }
        register {
            flowResource(120L)
        }
    }

    containers {
        container<Int> {
            bindToResource()
            coroutineScope {
                CoroutineScope(Dispatchers.IO)
            }
        }
        container<Long> {
            bindToResource()
            coroutineScope {
                CoroutineScope(Dispatchers.IO)
            }
        }
    }

    threads {
        eventThread<TestEvent>() bind { state: Int, event ->
            12
        }
        eventThread<AnotherEvent> {
            Logger.d("DEFAULT") { resource<Int>()().toString() }
        } tap {state: Int, event ->
            Logger.d("TAP") { state.toString() }
            this + SetLong(state.toLong())
        }
        eventThread<SetLong>() bind { state: Long, event ->
            event.new
        }
    }
}