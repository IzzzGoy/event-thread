package ru.alexey.event.threads.navgraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import ru.alexey.event.threads.LocalScope
import ru.alexey.event.threads.Scope
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.scope
import kotlin.jvm.JvmInline


@JvmInline
value class NavigationStack(
    val stack: List<ReadyScreen>
)

typealias ReadyScreen = Pair<Screen, Parameters>


/*
class NavGraph(
    private val scope: Scope,
    private val screens: Map<String, Screen>
) {
    val stack = scope.resolveOrThrow<NavigationStack>()

    fun push(screen: String, params: () -> Parameters) {
        scope + PushScreen(screens[screen] ?: error("Unknown screen: $screen"), params())
    }

    fun push(preparedScreen: NavigationDestination, params: () -> Parameters) {
        if (stack.value.stack.last().first.key == preparedScreen.name) return
        val parameters = params()
        preparedScreen.params.forEach {
            require(parameters.containsKey(it)) { "Missing parameter: ${it.simpleName}" }
        }
        scope + PushScreen(
            screens[preparedScreen.name] ?: error("Unknown screen: ${preparedScreen.name}"),
            params()
        )
    }
}



val LocalNavGraph = staticCompositionLocalOf<NavGraph> { error("No NavGraph found") }

@Composable
fun NavGraph(startScreen: NavigationDestination, block: NavGraphBuilder.() -> Unit) {
    val scope = LocalScope.current
    val navGraph = remember {
        NavGraphBuilder().apply(block)(scope)
    }
    navGraph.push(startScreen) { emptyMap() }
    val screens by navGraph.stack.collectAsState()
    CompositionLocalProvider(LocalNavGraph provides navGraph) {
        val (screen, params) = screens.stack.last()
        screen renderWith { params }
    }
}


@Composable
fun NavGraph(navigationScopeName: String = "Navigation", startScreen: NavigationDestination, block: NavGraphBuilder.() -> Unit) {
    scope(navigationScopeName) {
        NavGraph(startScreen, block)
    }
}*/
