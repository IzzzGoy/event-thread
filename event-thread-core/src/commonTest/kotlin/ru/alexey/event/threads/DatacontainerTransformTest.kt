package ru.alexey.event.threads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.flowResource
import kotlin.test.Test
import kotlin.test.assertEquals

data class Base(val value: Int)
data class Multiplier(val value: Int)
data class Product(val value: Int)

/**
 * Regression test for the `Ext.kt`/`foldWithProxy` cast that used to make `.transform()` throw
 * `ClassCastException` on every real (non-`Nothing`) combine - see the `known-issues-alpha14-review`
 * memory note. The current code casts `transform.action` itself, not its arguments, which JVM
 * type erasure doesn't check at the call site, so this now runs cleanly. Keeping this test so a
 * future refactor of `foldWithProxy` can't silently reintroduce the crash.
 */
class DatacontainerTransformTest {

    @Test
    fun transformCombinesTwoContainersWithoutCrashing() = runTest {
        val scope = scopeBuilder("transform-test") {
            val base by datacontainer(flowResource(Base(2))) { }
            val multiplier by datacontainer(flowResource(Multiplier(3))) { }

            val product by datacontainer(flowResource(Product(0))) {
                transform(base) { b, current -> current.copy(value = b.value * multiplier.value.value) }
                transform(multiplier) { m, current -> current.copy(value = base.value.value * m.value) }
            }

            // local delegated properties only build/register on first read - force it here so
            // `resolveOrThrow<Product>()` below can find it (README §5 documents the same
            // "force the read" requirement for scope-inheritance snapshotting).
            product
            threads { }
        }

        val builtScope = scope(emptyMap()).build()
        try {
            val productContainer = builtScope.resolveOrThrow<Product>()
            // the combine pipeline runs on real Dispatchers.Default coroutines, not runTest's
            // virtual clock - await it from a real dispatcher so withTimeout measures wall time.
            val result = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5000) { productContainer.first { it.value != 0 } }
            }
            assertEquals(Product(6), result)
        } finally {
            builtScope.close()
        }
    }
}
