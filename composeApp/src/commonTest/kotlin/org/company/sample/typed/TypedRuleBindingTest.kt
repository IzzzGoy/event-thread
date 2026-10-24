package org.company.sample.typed

import org.company.sample.SelectList
import org.company.sample.containers.SelectedListContainer
import org.company.sample.events.SelectListStateProcessor
import org.company.sample.events.SelectListStateProcessorRule
import org.company.sample.scopes.TodoTabsScopeDependencyBindings
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import ru.alexey.event.threads.di.koin.asDependencyProvider
import ru.alexey.event.threads.resources.flowResource
import kotlin.test.Test
import kotlin.test.assertIs

/** A schema rule is its binding's default: used when the provider binds nothing for the handler,
 * replaced by whatever it does bind. */
class TypedRuleBindingTest {

    private class Selected : SelectedListContainer() {
        override val source = flowResource("Work")
    }

    /** Ignores the selection - only its type matters here. */
    private class Pinned(deps: Deps) : SelectListStateProcessor(deps) {
        override suspend fun process(event: SelectList, state: String) = "Work"
    }

    private val deps = SelectListStateProcessor.Deps(Selected())

    @Test
    fun ruleIsTheDefault() {
        val provider = koinApplication { modules(typedTodoModule) }.koin.asDependencyProvider()
        assertIs<SelectListStateProcessorRule>(TodoTabsScopeDependencyBindings(provider).selectListStateProcessor(deps))
    }

    @Test
    fun aBoundImplementationOverridesTheRule() {
        val override = module { factory<SelectListStateProcessor> { (d: SelectListStateProcessor.Deps) -> Pinned(d) } }
        val provider = koinApplication { modules(typedTodoModule, override) }.koin.asDependencyProvider()
        assertIs<Pinned>(TodoTabsScopeDependencyBindings(provider).selectListStateProcessor(deps))
    }
}
