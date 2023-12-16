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
import kotlin.reflect.KClass


@JvmInline
value class NavigationStack(
    val stack: List<ReadyScreen>
)

typealias ReadyScreen = Pair<Screen, Parameters>



class NavGraph<NAV: NavigationDestination>(
    val screens: Map<KClass<out NAV>, () -> Screen>
) {

}
