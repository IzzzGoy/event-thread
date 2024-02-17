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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import ru.alexey.event.threads.Event
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.cache.cacheJsonResource
import ru.alexey.event.threads.cache.pathToJSON
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.navgraph.NavigationDestination
import ru.alexey.event.threads.navgraph.PopUp
import ru.alexey.event.threads.navgraph.navGraph
import ru.alexey.event.threads.navgraph.widget
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.resources.invoke
import ru.alexey.event.threads.resources.observable
import ru.alexey.event.threads.resources.param
import ru.alexey.event.threads.resources.resolve
import ru.alexey.event.threads.resources.resource
import ru.alexey.event.threads.resources.valueResource
import ru.alexey.event.threads.scopeholder.scopeHolder
import kotlin.random.Random


data object Global : Event
data object Counter : Event

data class SetString(val str: String) : StrictEvent

val jsonResource by resource {
    valueResource(Json { prettyPrint = true })
}

val intResource by observable {
    cacheJsonResource("test", "Test", jsonResource()())
}

val startScreenWidget by widget<String> { it, modifier ->
    Text(it)
    val holder = LocalScopeHolder.current
    Button(onClick = {
        holder + SecondScreen(
            mapOf(
                String::class to { "Hello world" }
            )
        )
    }
    ) {
        Text("Click")
    }
}

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

    scopeEmbedded(startScreenWidget.name) {
        config {
            createEventBus {
                coroutineScope {
                    CoroutineScope(Dispatchers.Main)
                }
            }
        }


        val intContainer by datacontainer(intResource()) {
            coroutineScope {
                CoroutineScope(Dispatchers.Main)
            }
            watcher {
                Logger.d("STATE_CONTAINER") { it }
            }
        }

        emitters {
            emitter {
                wrapFlow(
                    flow {
                        emit(SetString(Random.Default.nextBytes(16).toHexString()))
                        delay(1000)
                        emit(SetString(Random.Default.nextBytes(16).toHexString()))
                        delay(1000)
                        emit(SetString(Random.Default.nextBytes(16).toHexString()))
                    }
                )
            }
        }

        threads {
            eventThread<SetString>().then(intContainer) { _: String, setString: SetString ->
                setString.str
            }
        }
    }

    navGraph<OuterNavigationDestination>("Navigation", StartScreen) {
        StartScreen::class bind {
            content {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.Red),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    startScreenWidget(Modifier)
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

data object StartScreen : OuterNavigationDestination

data class SecondScreen(
    override val params: Parameters
) : OuterNavigationDestination