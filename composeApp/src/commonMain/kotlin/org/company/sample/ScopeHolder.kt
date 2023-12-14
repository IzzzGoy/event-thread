package org.company.sample

import co.touchlab.kermit.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import ru.alexey.event.threads.Event
import ru.alexey.event.threads.scopeholder.scopeHolder


data object Global : Event
data object Counter : Event

fun provideScopeHolder() = scopeHolder {

    Global::class consume "Global"
    Counter::class consume "StartScreen"

    scopeEmbedded("Counter") {
        config {
            createEventBus {
                watcher {
                    Logger.d("COUNTER") { it.toString() }
                }
            }
        }
        emitters {
            emitter {
                wrapFlow(
                    flow {
                        repeat(5) {
                            delay(300)
                            emit(Counter)
                        }
                    }
                )
            }
        }
    }

    scope("StartScreen", ::provideStartScreenScope) dependsOn "Counter"

    scopeEmbedded("Global") {
        config {
            createEventBus {
                watcher {
                    Logger.d("GLOBAL") { it.toString() }
                }
            }
        }
    }
}