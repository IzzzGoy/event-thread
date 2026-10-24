package ru.alexey.event.threads

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.flowResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Datacontainers are now keyed by (type, name), not type alone - `name` defaults to the
 * declaring property's own name (`val title by datacontainer(...) { }` registers under
 * `"title"`), so two differently-named same-typed containers in one scope are independent
 * instead of the second declaration silently returning the first. Resolving by type alone
 * (`resolveOrThrow<T>()`, no name) still works exactly as before whenever there's only one
 * container of that type - it only becomes ambiguous (and throws, rather than picking one
 * arbitrarily) once a scope actually has more than one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiBindingDatacontainerTest {

    @Test
    fun twoSameTypedContainersWithDifferentPropertyNamesAreIndependent() = runTest {
        // Pinned to this test's own dispatcher so advanceUntilIdle() can deterministically drive
        // the container's background fold/update machinery (otherwise it defaults to a real
        // Dispatchers.Default coroutine, whose propagation to `.value` would race this test's own
        // virtual-time assertions).
        val testScope = this
        val scope = scopeBuilder("multi-binding-independent") {
            val title by datacontainer(flowResource("")) { coroutineScope { testScope } }
            val subtitle by datacontainer(flowResource("")) { coroutineScope { testScope } }
            // Force both to build - `by` is lazy, only realized on first read.
            title.value
            subtitle.value
        }(emptyMap()).build()

        try {
            scope.resolveOrThrow<String>("title").update { "Title" }
            scope.resolveOrThrow<String>("subtitle").update { "Subtitle" }
            advanceUntilIdle()

            assertEquals("Title", scope.resolveOrThrow<String>("title").value)
            assertEquals("Subtitle", scope.resolveOrThrow<String>("subtitle").value)
        } finally {
            scope.close()
        }
    }

    @Test
    fun resolvingWithoutANameThrowsWhenMoreThanOneContainerOfThatTypeExists() = runTest {
        val scope = scopeBuilder("multi-binding-ambiguous") {
            val title by datacontainer(flowResource("")) { }
            val subtitle by datacontainer(flowResource("")) { }
            // Force both to build - `by` is lazy, only realized on first read.
            title.value
            subtitle.value
        }(emptyMap()).build()

        try {
            val error = assertFailsWith<Exception> { scope.resolveOrThrow<String>() }
            assertTrue("title" in error.message.orEmpty())
            assertTrue("subtitle" in error.message.orEmpty())
        } finally {
            scope.close()
        }
    }

    @Test
    fun aSingleContainerOfATypeStillResolvesWithNoNameNeeded() = runTest {
        val scope = scopeBuilder("multi-binding-single") {
            val todos by datacontainer(flowResource(listOf("a"))) { }
            todos.value
        }(emptyMap()).build()

        try {
            // No name given - same call shape as every pre-multi-binding scope in the repo.
            assertEquals(listOf("a"), scope.resolveOrThrow<List<String>>().value)
        } finally {
            scope.close()
        }
    }

    @Test
    fun explicitNameOverridesThePropertysOwnName() = runTest {
        val scope = scopeBuilder("multi-binding-explicit-name") {
            val whatever by datacontainer(flowResource("value"), name = "custom") { }
            whatever.value
        }(emptyMap()).build()

        try {
            assertEquals("value", scope.resolveOrThrow<String>("custom").value)
        } finally {
            scope.close()
        }
    }
}
