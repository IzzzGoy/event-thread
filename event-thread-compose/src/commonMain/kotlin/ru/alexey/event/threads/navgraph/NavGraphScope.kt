package ru.alexey.event.threads.navgraph

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import ru.alexey.event.threads.ExtendableEvent
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.valueResource
import ru.alexey.event.threads.scopeholder.ScopeHolderBuilder

inline fun <reified PUSH : NavigationDestination> ScopeHolderBuilder.navGraph(
    name: String,
    first: PUSH,
    crossinline builder: NavGraphBuilder<PUSH>.() -> Unit
) {
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
                valueResource(NavGraphBuilder<PUSH>().apply(builder)())
            }
            register {
                flowResource(listOf<ReadyScreen>())
            }
        }

        emitters {
            emitter {
                wrapFlow(flowOf(first))
            }
        }

        containers {
            container<List<ReadyScreen>> {
                bindToResource()
                coroutineScope {
                    CoroutineScope(Dispatchers.Main)
                }
            }
        }

        threads {
            eventThread<PUSH>() bind { stack: List<ReadyScreen>, event ->
                val screen = resource<NavGraph<PUSH>>().invoke().screens[event::class]?.invoke()
                    ?: error("Missing screen with name ${event.name}")
                screen.checkParams(event.params)
                stack + ReadyScreen(screen, event.params)
            }
        }
    }
}
