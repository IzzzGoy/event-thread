package org.company.sample

import co.touchlab.kermit.Logger
import ru.alexey.event.threads.Event
import ru.alexey.event.threads.scopeholder.scopeHolder


data object Global : Event

fun provideScopeHolder() = scopeHolder(
    Global::class to listOf("Global")
) {
    scoped("StartScreen", ::provideStartScreenScope)
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