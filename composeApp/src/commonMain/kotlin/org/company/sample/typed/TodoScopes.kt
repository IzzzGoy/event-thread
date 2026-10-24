package org.company.sample.typed

import org.company.sample.scopes.registerPersonalScope
import org.company.sample.scopes.registerTodoDomainScope
import org.company.sample.scopes.registerTodoDraftScope
import org.company.sample.scopes.registerTodoTabsScope
import org.company.sample.scopes.registerWorkScope
import org.company.sample.todoNavigation
import org.koin.dsl.koinApplication
import ru.alexey.event.threads.di.DependencyProvider
import ru.alexey.event.threads.di.koin.asDependencyProvider
import ru.alexey.event.threads.scopeholder.ScopeHolder
import ru.alexey.event.threads.scopeholder.scopeHolder

/**
 * The example app's ScopeHolder built from typed, generated scopes - a drop-in alternative to
 * [org.company.sample.provideTodoScopeHolder] (switch in App.kt). Same scope keys, params types
 * and containers, so the app's UI works unchanged against either. Relations come from scopes.json
 * via each generated `register…()`: TodoTabs dependsOn TodoDomain; Work and Personal consume
 * AddLimitReached. Navigation stays the DSL nav graph.
 */
fun provideTypedTodoScopeHolder(): ScopeHolder =
    typedTodoScopeHolder(koinApplication { modules(typedTodoModule) }.koin.asDependencyProvider())

fun typedTodoScopeHolder(dependencyProvider: DependencyProvider): ScopeHolder = scopeHolder {
    dependencyProvider(dependencyProvider)

    registerTodoDomainScope()
    registerWorkScope()
    registerPersonalScope()
    registerTodoTabsScope()
    registerTodoDraftScope()

    todoNavigation()
}
