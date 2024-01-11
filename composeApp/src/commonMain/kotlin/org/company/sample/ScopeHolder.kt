package org.company.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import io.ktor.http.parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import ru.alexey.event.threads.Event
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.cache.cacheJsonRecourse
import ru.alexey.event.threads.cache.pathToJSON
import ru.alexey.event.threads.navgraph.NavigationDestination
import ru.alexey.event.threads.navgraph.PopUp
import ru.alexey.event.threads.navgraph.navGraph
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.resolve
import ru.alexey.event.threads.resources.valueResource
import ru.alexey.event.threads.scopeholder.scopeHolder
import kotlin.random.Random


data object Global : Event
data object Counter : Event

data class SetString(val str: String) : StrictEvent

@OptIn(ExperimentalStdlibApi::class)
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
                Logger.d("PATH") { pathToJSON("test") }
                cacheJsonRecourse("test", "Test", Json { prettyPrint = true })
            }
        }

        emitters {
            emitter {

                wrapFlow(flowOf(SetString(Random.Default.nextBytes(16).toHexString())))
            }
        }

        containers {
            container<String> {
                bindToResource()
                watcher {
                    Logger.d("STATE_CONTAINER") { it }
                }
            }
        }

        threads {
            eventThread<SetString>() bind { state: String, event ->
                event.str
            }
        }
    }

    navGraph<OuterNavigationDestination>("Navigation", StartScreen) {
        StartScreen::class bind {
            registerWidget<String>("StartScreenWidget") { it, modifier ->
                Text(it)
                val holder = LocalScopeHolder.current
                Button(onClick = { holder + SecondScreen(mapOf(String::class to { "Hello world" })) }) {
                    Text(
                        "Click"
                    )
                }
            }

            content {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.Red),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    it["StartScreenWidget"]?.Content(Modifier)
                }
            }
        }
        SecondScreen::class bind {
            require {
                String::class()
            }
            content {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.Blue),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(this@content.resolve<String>())
                    val holder = LocalScopeHolder.current
                    Button(onClick = { holder + PopUp }) { Text("Click") }
                }
            }
        }
    }
}


sealed interface OuterNavigationDestination : NavigationDestination

data object StartScreen : OuterNavigationDestination {
    override val name: String = "StartScreen"
}

data class SecondScreen(
    override val params: Parameters
) : OuterNavigationDestination {
    override val name: String = "SecondScreen"
}