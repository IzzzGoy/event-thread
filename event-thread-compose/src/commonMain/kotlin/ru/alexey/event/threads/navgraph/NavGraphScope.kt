package ru.alexey.event.threads.navgraph

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import ru.alexey.event.threads.ExtendableEvent
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeholder.ScopeHolderBuilder

inline fun <reified T : ExtendableEvent> ScopeHolderBuilder.navGraphScope(name: String = "Navigation") {
    scopeEmbedded(name) {
        config {
            createEventBus {
                coroutineScope {
                    CoroutineScope(Dispatchers.Main)
                }
            }
        }

        resources {
            register {
                flowResource(NavigationStack(emptyList()))
            }
        }

        containers {
            container<NavigationStack> {
                bindToResource()
                coroutineScope { CoroutineScope(Dispatchers.Main) }
            }
        }

        threads {
            /*eventThread<PushScreen>() bind { stack: NavigationStack, event ->
                NavigationStack(stack.stack + (event.screen to event.parameters))
            }*/
        }
    }
}
