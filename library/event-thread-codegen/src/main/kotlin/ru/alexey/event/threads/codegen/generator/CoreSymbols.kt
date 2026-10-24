package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.asClassName
import ru.alexey.event.threads.OutputMismatchBehavior as CoreOutputMismatchBehavior
import ru.alexey.event.threads.Privacy as CorePrivacy
import ru.alexey.event.threads.bus.Event as CoreEvent
import ru.alexey.event.threads.di.DependencyProvider as CoreDependencyProvider
import ru.alexey.event.threads.scopeholder.typed.ContainerSnapshot as CoreContainerSnapshot
import ru.alexey.event.threads.scopeholder.typed.DatacontainerTyped as CoreDatacontainerTyped
import ru.alexey.event.threads.scopeholder.typed.EventHandler as CoreEventHandler
import ru.alexey.event.threads.scopeholder.ScopeHolderBuilder as CoreScopeHolderBuilder
import ru.alexey.event.threads.scopeholder.typed.ScopeKey as CoreScopeKey
import ru.alexey.event.threads.scopeholder.typed.ScopeKeyFragment as CoreScopeKeyFragment
import ru.alexey.event.threads.scopeholder.typed.ScopeTyped as CoreScopeTyped
import ru.alexey.event.threads.scopeholder.typed.ScopeWiring as CoreScopeWiring

/**
 * Every event-thread-core type generated code refers to, in one place - by class reference, so a
 * rename/move in core fails this module's compilation rather than producing generated code that
 * no longer compiles in the consumer.
 */
internal object CoreSymbols {
    val ScopeTyped: ClassName = CoreScopeTyped::class.asClassName()
    val ScopeKey: ClassName = CoreScopeKey::class.asClassName()
    val ScopeWiring: ClassName = CoreScopeWiring::class.asClassName()
    val DependencyProvider: ClassName = CoreDependencyProvider::class.asClassName()
    val DatacontainerTyped: ClassName = CoreDatacontainerTyped::class.asClassName()
    val ContainerSnapshot: ClassName = CoreContainerSnapshot::class.asClassName()
    val Event: ClassName = CoreEvent::class.asClassName()
    val StateProcessor: ClassName = CoreEventHandler.StateProcessor::class.asClassName()
    val EventCascader: ClassName = CoreEventHandler.EventCascader::class.asClassName()
    val SideEffect: ClassName = CoreEventHandler.SideEffect::class.asClassName()
    val Privacy: ClassName = CorePrivacy::class.asClassName()
    val OutputMismatchBehavior: ClassName = CoreOutputMismatchBehavior::class.asClassName()
    val ScopeKeyFragment: ClassName = CoreScopeKeyFragment::class.asClassName()
    val DependencyProviderBindings: ClassName = ru.alexey.event.threads.scopeholder.typed.DependencyProviderBindings::class.asClassName()
    val DummyProvider: ClassName = ru.alexey.event.threads.di.DummyProvider::class.asClassName()
    val EventBus: ClassName = ru.alexey.event.threads.bus.EventBus::class.asClassName()
    val CoroutineScope: ClassName = kotlinx.coroutines.CoroutineScope::class.asClassName()
    val Dispatchers: ClassName = kotlinx.coroutines.Dispatchers::class.asClassName()
    val ScopeHolderBuilder: ClassName = CoreScopeHolderBuilder::class.asClassName()

    /** Top-level extension - no class to reference; the package still comes from one, so a
     * package move is caught. */
    val snapshot = MemberName(CoreContainerSnapshot::class.java.packageName, "snapshot")

    /** `ScopeKey.extend(key) { }` and `ScopeHolderBuilder.include(...)` - typed/Ext.kt extensions. */
    val extend = MemberName(CoreScopeKey::class.java.packageName, "extend")
    val include = MemberName(CoreScopeKey::class.java.packageName, "include")
}
