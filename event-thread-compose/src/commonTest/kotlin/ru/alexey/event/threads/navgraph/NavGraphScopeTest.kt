package ru.alexey.event.threads.navgraph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ru.alexey.event.threads.resources.Parameters
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.scopeHolder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

sealed interface TestDestination : NavigationDestination
data object Home : TestDestination
data class Details(override val params: Parameters) : TestDestination

@OptIn(ExperimentalCoroutinesApi::class)
class NavGraphScopeTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildHolder(): ScopeHolder = scopeHolder {
        navGraph<TestDestination>("nav-test", Home) {
            Home::class bind { content { } }
            Details::class bind {
                require { String::class() }
                content { }
            }
        }
    }

    @Test
    fun pushLooksUpAndAppendsTheRegisteredScreen() = runTest {
        val holder = buildHolder()
        try {
            val stack = holder.findOrLoad("nav-test").resolveOrThrow<List<ReadyScreen>>()
            assertEquals(1, stack.value.size)

            holder + Details(mapOf(String::class to { "param" }))

            assertEquals(2, stack.value.size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun popUpGuardsTheRoot() = runTest {
        val holder = buildHolder()
        try {
            val stack = holder.findOrLoad("nav-test").resolveOrThrow<List<ReadyScreen>>()

            holder + PopUp

            assertEquals(1, stack.value.size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun popToScreenPopsBackToATargetPresentInTheStack() = runTest {
        val holder = buildHolder()
        try {
            val stack = holder.findOrLoad("nav-test").resolveOrThrow<List<ReadyScreen>>()
            val homeScreen = stack.value.first().first

            holder + Details(mapOf(String::class to { "a" }))
            assertEquals(2, stack.value.size)

            holder + PopToScreen(homeScreen)

            assertEquals(1, stack.value.size)
            assertEquals(homeScreen.key, stack.value.first().first.key)
        } finally {
            holder.close()
        }
    }

    @Test
    fun popToScreenLeavesTheStackUnchangedWhenTargetIsAbsent() = runTest {
        val holder = buildHolder()
        try {
            val stack = holder.findOrLoad("nav-test").resolveOrThrow<List<ReadyScreen>>()

            val foreignScreen = ScreenBuilder().apply { content { } }()

            holder + PopToScreen(foreignScreen)

            assertEquals(1, stack.value.size)
        } finally {
            holder.close()
        }
    }
}
