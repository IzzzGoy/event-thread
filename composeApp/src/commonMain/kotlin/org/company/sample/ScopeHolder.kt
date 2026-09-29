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
import kotlinx.serialization.json.Json
import ru.alexey.event.threads.Event
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.LocalScopeHolder
import ru.alexey.event.threads.StrictEvent
import ru.alexey.event.threads.cache.cacheJsonResource
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.datacontainer.datacontainerKey
import ru.alexey.event.threads.datacontainer.parent
import ru.alexey.event.threads.navgraph.NavigationDestination
import ru.alexey.event.threads.navgraph.PopUp
import ru.alexey.event.threads.navgraph.navGraph
import ru.alexey.event.threads.navgraph.widget
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.resources.invoke
import ru.alexey.event.threads.resources.observable
import ru.alexey.event.threads.resources.param
import ru.alexey.event.threads.resources.resolve
import ru.alexey.event.threads.resources.resolveOrDefault
import ru.alexey.event.threads.resources.resource
import ru.alexey.event.threads.resources.valueResource
import ru.alexey.event.threads.scopeBuilder
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

val intDCKey = datacontainerKey(intResource())

val startScreenWidget by widget<String> { it, modifier ->
    Text(it)
    val holder = LocalScopeHolder.current

    Column {
        LocalScope.current.eventBus.metadata.forEach {
            Text(it.toString())
        }
        LocalScope.current + Global
        Button(onClick = { holder + SecondScreen(mapOf(String::class to { "Hello world" })) }) {
            Text("Click")
        }
    }
}

/*@OptIn(ExperimentalStdlibApi::class)
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

    scopeEmbedded("Global") { params ->
        config {
            createEventBus {
                watcher {
                    Logger.d("GLOBAL") { "${params.resolve<String>()}: $it" }
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


        *//* val intContainer by datacontainer(intDCKey) {
             coroutineScope {
                 CoroutineScope(Dispatchers.Main)
             }
             watcher {
                 Logger.d("STATE_CONTAINER") { it }
             }
         }*//*

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
            thread<SetString> {
                description {
                    "Just set string dude :)"
                }
            }*//*.then(intContainer) { _: String, setString: SetString ->
                setString.str
            }*//*
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
                    Button(
                        onClick = {
                            holder + PopUp
                        }
                    ) {
                        Text("Click")
                    }
                }
            }
        }
    }
}*/


sealed interface OuterNavigationDestination : NavigationDestination

data object StartScreen : OuterNavigationDestination

data class SecondScreen(
    override val params: Parameters
) : OuterNavigationDestination


data class Update(val newValue: Int) : StrictEvent

val text by observable<Int> {
    flowResource(it.resolve<Int>())
}

val text2 by observable<String> {
    flowResource("")
}

fun provideScopeHolderTest() = scopeHolder {

    "First" implements "Parent"
    "Test" implements "Parent"

    scopeEmbedded("First") { scopeParams ->
        val dc1 by datacontainer(
            text<Int> { param { 13 } }
        ) {

        }

        val dc2 by parent<String>()

        val dc by datacontainer(text {
            param<Int> {
                scopeParams.resolveOrDefault(12)
            }
        }) {
            transform(dc1) { it, d ->
                d
            }
            transform(dc2) { it, d ->
                Logger.d("SUCESS") { it }
                d
            }
        }



        threads {
            thread<Update> {

            }.then(dc) { _, event ->
                event.newValue
            }.then(dc2) { _, event ->
                event.newValue.toString()
            }
        }
    }
    scopeEmbedded("Parent") {
        val t2 by datacontainer(text2()) {

        }
        t2.value

        threads {
            thread<Counter>()
        }
    }

    scope("Test") { name, parents ->
        scopeBuilder(name = name, parents = parents) {
            val dc2 by parent<String>()
            val dc by datacontainer(text { param { 13 } } ) {
                transform(dc2) { t, i ->
                    i + t.length
                }
            }
            dc.value
        }
    }
}