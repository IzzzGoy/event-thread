package org.company.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import ru.alexey.event.threads.Event
import ru.alexey.event.threads.navgraph.NavigationDestination
import ru.alexey.event.threads.navgraph.navGraph
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.valueResource
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

    scopeEmbedded("StartScreenWidget") {
        config {
            createEventBus {
                coroutineScope {
                    CoroutineScope(Dispatchers.Main)
                }
            }
        }

        resources {
            register {
                flowResource("test")
            }
        }

        containers {
            container<String> {
                bindToResource()
            }
        }
    }

    navGraph<OuterNavigationDestination>("Navigation", StartScreen) {
        StartScreen bind {
            registerWidget<String>("StartScreenWidget") {
                Text(it)
            }

            content {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.Red),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    it["StartScreenWidget"]?.Content()
                }
            }
        }
    }
}


sealed interface OuterNavigationDestination : NavigationDestination

data object StartScreen : OuterNavigationDestination {
    override val name: String = "StartScreen"
}