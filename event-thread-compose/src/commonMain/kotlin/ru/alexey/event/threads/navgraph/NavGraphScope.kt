package ru.alexey.event.threads.navgraph

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import ru.alexey.event.threads.ExtendableEvent
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.invoke
import ru.alexey.event.threads.resources.observable
import ru.alexey.event.threads.resources.resource
import ru.alexey.event.threads.resources.valueResource
import ru.alexey.event.threads.scopeholder.ScopeHolderBuilder

inline fun <reified PUSH : NavigationDestination> ScopeHolderBuilder.navGraph(
    name: String,
    first: PUSH,
    crossinline builder: NavGraphBuilder<PUSH>.() -> Unit
) {

    PUSH::class consume name

    scopeEmbedded(name) {
        config {
            createEventBus {
                coroutineScope {
                    CoroutineScope(Dispatchers.Main)
                }
            }
        }

        val navGraph by resource {
            valueResource(NavGraphBuilder<PUSH>().apply(builder)())
        }
        val screens by observable {
            flowResource(listOf<ReadyScreen>())
        }


        emitters {
            emitter {
                wrapFlow(flowOf(first))
            }
        }

        val stack by datacontainer(screens()) {
            coroutineScope {
                CoroutineScope(Dispatchers.Main)
            }
        }

        threads {
            eventThread<PUSH>().then(stack) { stack: List<ReadyScreen>, event ->
                description {
                    "Event for navigation to next screen: ${event::class.simpleName} with params ${event.params.keys}"
                }
                val screen = navGraph()().screens[event::class]?.invoke()
                    ?: error("Missing screen: $event")
                screen.checkParams(event.params)
                stack + ReadyScreen(screen, event.params)
            }
            eventThread<PopUp>().then(stack) { stack: List<ReadyScreen>, _ ->
                if (stack.size > 1) {
                    stack.dropLast(1)
                } else {
                    stack
                }
            }

            eventThread<PopToScreen>().then(stack) { stack: List<ReadyScreen>, event ->
                if (event.screen == null) {
                    if (stack.size > 1) {
                        stack.dropLast(1)
                    } else {
                        stack
                    }
                } else {
                    stack.dropLastWhile {
                        (it.first.key == event.screen.key) and (stack.first().first.key != event.screen.key)
                    }
                }
            }
        }
    }
}
