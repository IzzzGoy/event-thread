package org.company.sample.typed

import org.company.sample.TodoDraft
import org.company.sample.scopes.TodoDomainScopeContract
import org.company.sample.scopes.TodoDomainScopeDependencyBindings
import org.company.sample.scopes.TodoDraftScopeContract
import org.company.sample.scopes.TodoDraftScopeDependencyBindings
import org.company.sample.scopes.TodoTabsScopeContract
import org.company.sample.scopes.TodoTabsScopeDependencyBindings
import org.koin.dsl.koinApplication
import ru.alexey.event.threads.di.koin.asDependencyProvider

/*
 * The app's typed scopes checked against the schema - generated contracts (see
 * build/generated/eventThread/test), run against the real bindings: typedTodoModule through the
 * generated DependencyProvider adapters. Work/Personal (file I/O) are in androidUnitTest.
 */

private fun appProvider() = koinApplication { modules(typedTodoModule) }.koin.asDependencyProvider()

class TodoDomainContractTest : TodoDomainScopeContract() {
    override fun bindings() = TodoDomainScopeDependencyBindings(appProvider())
}

class TodoTabsContractTest : TodoTabsScopeContract() {
    override fun bindings() = TodoTabsScopeDependencyBindings(appProvider())
}

class TodoDraftContractTest : TodoDraftScopeContract() {
    override fun bindings() = TodoDraftScopeDependencyBindings(appProvider())
    override fun params() = TodoDraft.Params()
}
