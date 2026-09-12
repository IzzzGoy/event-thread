# Research
[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fizzzgoy%2Fevent-thread-core%2Fmaven-metadata.xml&label=maven-central)](https://central.sonatype.com/artifact/io.github.izzzgoy/event-thread-core)

## Description


Here is a solution for declarative application description. It allows describing the desired logic of application behavior using configuration tools.

This toolkit enables the description of the application's operation on all necessary levels, from working with the graphical interface to interacting with data in the cache.

## Key concepts:

**Event** - something that happened. A plain marker interface (`StrictEvent`, `ExtendableEvent`) implemented by a class/object; carries whatever payload it needs as constructor properties.

**EventBus** - the dispatcher living inside every `Scope`. Events sent into it (`scope + MyEvent(...)`) are routed to every `EventThread` registered for that event's type.

**EventThread (Thread)** - an entity that can handle events. Contains a list of handlers (actions) that run when a matching event arrives. A thread can *modify* a container, *cascade* into another event, or just *consume* the event as a side effect.

**Resource** - an object that is required for the execution of certain logic. It is created only when demanded and releases resources after the logic is executed.

> At the moment, there are two types of resources:
>
> Basic - contains only a stored object.
>
> Observable - provides a StateFlow of objects of the specified type.

**Container (Datacontainer)** - contains an abstraction over certain data, their state, and the logic of interacting with them. It wraps a `StateFlow`, built on top of an observable resource. If resources abstract over some data, then the container describes methods for interacting with this data. Containers can also undergo concatenation (`.transform`), allowing the use of one or more containers to compose the final, derived state.

**Scope** - an entity that describes a certain part of the graphical interface (screen or individual widget) and contains all the necessary configuration (event bus, containers, threads) to ensure its operation. Scopes are loaded on demand and released when nothing references them anymore, and can inherit from one another (`implements`).

**ScopeHolder** - the registry/factory for all scopes in an application. Built once via `scopeHolder { }` and handed to the Compose tree; loads/frees scopes by name as they're needed.

**Emitter** - a way to feed events into a scope's `EventBus` from an external reactive source (a `Flow`, a timer, a platform callback, etc.) without wiring that source through the widget layer.

## Installation


```kotlin
commonMain {
    dependencies {
        implementation("io.github.izzzgoy:event-thread-core:$event_thread_version")
        implementation("io.github.izzzgoy:event-thread-compose:$event_thread_version")
        implementation("io.github.izzzgoy:event-thread-network:$event_thread_version")
        implementation("io.github.izzzgoy:event-thread-cache:$event_thread_version")
        //available only on android/ios target
        //KVault under the hood
        implementation("io.github.izzzgoy:event-thread-secure:$event_thread_version")
    }
}

commonTest {
    dependencies {
        // static event-flow graph checks + the scenario/dynamic test DSL - see §10 Testing
        implementation("io.github.izzzgoy:event-thread-test:$event_thread_version")
    }
}
```

> **⚠️ `event-thread-network` and `event-thread-secure` are on pause.** Both have known,
> unfixed correctness issues (a shared `HttpClient` closed after the first request, a
> `WebSocket` resource with no reconnect handling, an encrypted resource whose `update()` is a
> no-op, an encryption key derived from a non-cryptographic seeded PRNG) and no active
> maintenance right now - somewhere between *deprecated* and *outdated*, not a recommendation
> against a future fix. Don't build on them for new code; `event-thread-core` +
> `event-thread-compose` + `event-thread-cache` are the maintained set.

***

## Usage

A runnable, more elaborate reference implementing everything below (events, containers, threads,
navigation, widgets, scope inheritance, composite state) lives in the `composeApp` module's
`org.company.sample` package - a small local todo-list app. It's the fastest way to see the
pieces wired together end to end.

### 1. Declare events

Events are plain classes/objects implementing `StrictEvent` (exactly one thread handles it,
dispatch is deterministic) or `ExtendableEvent` (every thread whose declared type is a supertype
of the event's runtime type gets a chance to handle it - useful for sealed hierarchies).

```kotlin
import ru.alexey.event.threads.bus.StrictEvent

data class AddTodo(val text: String) : StrictEvent
data class ToggleTodo(val id: Long) : StrictEvent
```

### 2. Describe a scope: containers + threads

A scope is declared with `scopeEmbedded(name) { ... }` inside a `scopeHolder { }` block.
Inside it:

- `datacontainer(source) { }` declares a piece of reactive state, backed by a `Resource`
  (`flowResource(initial)` for in-memory state, or a persisted one - see [Caching](#caching)).
- `threads { }` registers `EventThread`s. `.then(container) { current, event -> newValue }`
  updates a container in response to an event; `.then { event -> otherEvent }` cascades into
  another event; `.end { event -> ... }` just runs a side effect.

```kotlin
import ru.alexey.event.threads.datacontainer.datacontainer
import ru.alexey.event.threads.resources.flowResource
import ru.alexey.event.threads.scopeholder.scopeHolder

fun provideScopeHolder() = scopeHolder {
    scopeEmbedded("Todos") {
        val todos by datacontainer(flowResource(emptyList<Todo>())) { }

        threads {
            thread<AddTodo>().then(todos) { list, event ->
                list + Todo(id = 0, text = event.text)
            }
            thread<ToggleTodo>().then(todos) { list, event ->
                list.map { if (it.id == event.id) it.copy(done = !it.done) else it }
            }
        }
    }
}
```

A container declared with `val x by datacontainer(...) { }` is a plain Kotlin delegated
property: the first read builds it (and registers it in the scope), later reads return the same
instance. Registering more than one `thread<T>()` for the same event type merges their actions
instead of replacing them, unless a later registration explicitly opts in to replace with
`override(true)` in `thread<T> { override(true) }`.

### 3. Wire it into Compose

- `ScopeHolder(::provideScopeHolder) { }` provides the holder to the composition.
- `scope(name) { }` loads (or reuses) the named scope for as long as the composable stays in
  the tree, and provides it via `LocalScope`.
- `LocalScope.current.resolveOrThrow<T>()` (or the `by LocalScope.current` delegate) resolves a
  container by its value type and returns its `StateFlow<T>`, ready for `collectAsState()`.
- `LocalScopeHolder.current` gives you the holder itself, so any composable can dispatch events
  with `holder + AddTodo("Buy milk")` - it's routed by event type, not by which scope happens to
  be locally in scope.

```kotlin
@Composable
fun TodosScreen() {
    val holder = LocalScopeHolder.current

    scope("Todos") {
        val todos by LocalScope.current.resolveOrThrow<List<Todo>>().collectAsState()

        Column {
            todos.forEach { todo ->
                Row {
                    Checkbox(checked = todo.done, onCheckedChange = { holder + ToggleTodo(todo.id) })
                    Text(todo.text)
                }
            }
            Button(onClick = { holder + AddTodo("New task") }) { Text("Add") }
        }
    }
}
```

Container lookup is by *type*, not by name - a scope can only hold one resolvable container per
distinct type. If two containers in the same scope would erase to the same type (e.g. two
`List<Todo>`), wrap one in a small dedicated data class so they stay independently resolvable.

### 4. Composite (derived) state

A container can be derived from one or more *other* containers of the same scope with
`.transform(otherContainer) { otherValue, currentValue -> newValue }` inside its
`datacontainer { }` block. Each `.transform` call folds in one more input; chain several to
combine more than two sources. The result recomputes reactively whenever any of the transformed
containers changes:

```kotlin
val showCompleted by datacontainer(flowResource(true)) { }

val visibleTodos by datacontainer(flowResource(VisibleTodos(emptyList()))) {
    transform(todos) { list, _ -> VisibleTodos(list) }
    transform(showCompleted) { show, current ->
        if (show) current else VisibleTodos(current.items.filterNot { it.done })
    }
}
```

`.transform` only combines containers that live in the *same* scope - it reads the other
container directly, not by name, so it can't reach into a different, independently-loaded
scope. If you need to aggregate state living in separate scopes, do it explicitly with events
(have each scope emit an update event that a shared scope's thread folds into its own state).

### 5. Scopes and the ScopeHolder DSL

- `scopeEmbedded(name) { params -> ... }` registers a scope factory. `params: Parameters`
  carries whatever was passed when the scope was loaded (`scope(name, parameters = ...)` from
  Compose, or `holder.load(name) { parameters }` directly) - read it with
  `params.resolve<T>()` / `params.resolveOrDefault(default)`.
- `config { createEventBus { } }` customizes the scope's event bus: `watcher { event -> }` taps
  every event for logging/analytics, and `coroutineScope { }` picks which `CoroutineScope` the
  bus runs on. Containers have their own, independent `coroutineScope { }` inside their
  `datacontainer(source) { }` block (defaults to `Dispatchers.Default`).
- `onError { event, error -> }` (also inside `createEventBus { }`) registers a handler for
  exceptions thrown by a watcher or by a thread's action while processing `event`. Without this,
  an action that throws would otherwise crash the bus's internal dispatch coroutine and silently
  stop it from processing any further events for that scope; with or without a handler
  registered, a thrown exception now only aborts the *rest of that one action chain* (e.g. the
  `.then()` calls after the one that threw don't run) - other subscribers and later events are
  unaffected. Register as many handlers as you need (they all run, in registration order); with
  none registered, the bus falls back to a diagnostic `println` so failures stay visible instead
  of vanishing:

  ```kotlin
  config {
      createEventBus {
          onError { event, error -> logger.error("event $event failed", error) }
      }
  }
  ```
- `"Child" implements "Parent"` copies `Parent`'s thread registrations and *already-built*
  containers into `Child` when `Child` is loaded - each implementer gets its own independent
  copy (the parent's `scopeEmbedded` body re-runs fresh per implementer, it's a template, not a
  shared instance).

  **Important:** because inheritance copies a *snapshot* of the parent's containers at the
  moment the child is constructed, any container the parent wants inherited must be forced to
  build *before* that snapshot is taken - containers built lazily (the default) are only
  realized on first read, which normally happens too late. Force it right after declaring it:

  ```kotlin
  scopeEmbedded("TodoListBase") { params ->
      val todos by datacontainer(cacheJsonResource(params.resolveOrDefault("todos_default"), emptyList(), Json)) { }
      todos.value // force it to build now, so `implements` can snapshot it into the child

      threads {
          thread<AddTodo>().then(todos) { list, event -> list + Todo(0, event.text) }
      }
  }

  scopeEmbedded("Work") { }
  "Work" implements "TodoListBase"

  scopeEmbedded("Personal") { }
  "Personal" implements "TodoListBase"
  ```

  Loading `"Work"` and `"Personal"` with different parameters (e.g. a different cache key) gives
  each its own independent, persisted list while sharing all the CRUD logic declared once on the
  base scope. See `TodoListBase`/`Work`/`Personal` in the sample app for the full, working
  version of this, including the Compose side (switching which scope is currently mounted).

- A child can also pull in one specific container from an implemented parent explicitly, with
  `val x by parent<T>()`, instead of relying on `implements` to copy it in as part of the whole
  parent template.
- `"Child" dependsOn "Parent"` loads `Parent` automatically whenever `Child` is loaded (and frees
  it when `Child` is freed, unless something else also depends on it) - unlike `implements`, this
  doesn't copy anything into `Child`; it just piggybacks one scope's lifecycle onto another's, so
  a supporting scope with no UI of its own (a background listener, a domain/business-rule scope)
  can stay alive for as long as the scope that actually gets mounted from Compose, without
  Compose needing to know it exists.

### 6. Caching

`event-thread-cache` provides persisted, `ObservableResource`-backed containers:

```kotlin
import ru.alexey.event.threads.cache.cacheJsonResource

val todos by datacontainer(cacheJsonResource("todos", emptyList<Todo>(), Json)) { }
```

`cacheJsonResource(key, initial, json)` loads `key` from local storage on first access (falling
back to `initial` if missing/corrupt) and persists every update made through the container. There's
a binary counterpart, `cacheBinaryResource(key, initial, cbor)`, for `Cbor`-serialized storage.

### 7. Navigation

`navGraph<NavDestination>(name, initialDestination) { }` (from `event-thread-compose`) registers
a whole navigation stack as a scope. Destinations are events (implement `NavigationDestination`,
which extends `ExtendableEvent`); dispatching one pushes the matching screen, `holder + PopUp`
pops it.

```kotlin
sealed interface AppDestination : NavigationDestination
data object HomeDestination : AppDestination
data class DetailsDestination(val id: Long) : AppDestination {
    override val params: Parameters get() = mapOf(DetailsParams::class to { DetailsParams(id) })
}

fun provideScopeHolder() = scopeHolder {
    // ...other scopes...

    navGraph<AppDestination>("Navigation", HomeDestination) {
        HomeDestination::class bind {
            content { HomeScreen() }
        }
        DetailsDestination::class bind {
            require { DetailsParams::class() }
            content { DetailsScreen(resolve<DetailsParams>()) }
        }
    }
}
```

Render the current screen with the `NavGraph("Navigation")` composable. Only the top of the
stack is ever composed, so anything that needs to outlive one screen (state a later screen
should still see after popping back) must live in a scope mounted *above* the nav graph, not
inside an individual destination's own composable.

### 8. Widgets

A `Widget` is a small, reusable display bound to a fixed scope name and container type - drop it
anywhere without re-wiring `LocalScope`/`resolveOrThrow` at each call site:

```kotlin
import ru.alexey.event.threads.widget.createWidget

val remainingCountWidget = createWidget<List<Todo>>("Todos") { todos, modifier ->
    Text("${todos.count { !it.done }} left", modifier = modifier)
}

// anywhere in the composition, once "Todos" is loaded elsewhere:
remainingCountWidget(Modifier.padding(end = 8.dp))
```

Because a widget is bound to one fixed scope name, scopes that exist in multiple independent
copies (like `Work`/`Personal` above) need one widget instance per copy.

### 9. Cross-scope events

By default `holder + event` broadcasts to every currently-loaded scope with a matching thread -
this is usually what you want (only the scopes that are actually mounted react). If you need to
route an event to specific scopes regardless of what else is loaded, declare it explicitly in the
`scopeHolder { }` block:

```kotlin
MyGlobalEvent::class consume listOf("ScopeA", "ScopeB")
```

This also applies to events a scope emits itself (`eventBus += event` from inside a `thread { }`
block, not just `holder + event`) - any event flowing through a scope's bus is checked against
`consume` mappings by its *actual* registered type and routed only to the scopes configured for
that type, excluding the scope that emitted it. Delivery to each configured receiver happens
exactly once per emission and isn't itself re-broadcast, so two scopes both configured to receive
the same event type won't end up echoing it back and forth.

This is the mechanism behind a headless "domain" scope that talks to UI scopes purely through
events - see the [scenarios](#common-scenarios) below.

### 10. Testing

`event-thread-test` is a separate, test-only module (add it to `commonTest`, see
[Installation](#installation)) with two complementary tools: a **static** event-flow graph built
from a scope's declared metadata (what *could* happen), and a **runtime scenario DSL** built on a
real dispatch trace (what *did* happen in one run). Neither needs anything beyond a normal
`ScopeHolder`/`scopeEmbedded` declaration - no test-only API on the production side.

#### Static event-flow graph

Every cascading `.then { event -> otherEvent }` records the event type it produces, inferred from
the lambda's return type - `ScopeMetadata.toEventGraph()` / `ScopeHolderMetadata.toEventGraph()`
turn that into an `EventGraph` you can assert against with a track-shaped DSL:

```kotlin
import ru.alexey.event.threads.test.graph.invoke // needed for the `graph("Scope") { }` operator
import ru.alexey.event.threads.scopeholder.generateActiveSchema

val holder = provideScopeHolder()
holder.findOrLoad("Todos")
val graph = holder.generateActiveSchema().toEventGraph()

graph("Todos") {
    reaches("AddTodo", "TodoAdded")       // a direct, literal hop - not just "eventually reachable"
    doesNotReach("SomethingUnhandled")    // negation of the one-arg reaches()
    orphan("ReceiptEmailQueued")          // produced by a cascade, but nothing handles it
}
graph.assertNoCycles()                    // advisory only - legitimate retry/convergent loops exist
```

`reaches(a, b, c)` checks the *literal* chain `a -> b -> c`, hop by hop - it deliberately rejects a
transitive shortcut (`reaches("A", "C")` fails if only `A -> B -> C` exists, not a direct `A -> C`).
Only the pure return-based `.then { }` shape is captured; an imperative `eventBus += event` inside a
`.then`/`.end` body stays invisible to the static graph (it's still visible at runtime - see below).

#### Scenario/dynamic tests

For asserting what a scope actually did during one real, deterministic dispatch - not what its
declared shape allows - wire a `ScenarioRecorder` into the scope's own bus config and drive it under
`kotlinx-coroutines-test`:

```kotlin
import ru.alexey.event.threads.test.scenario.*
import kotlinx.coroutines.test.advanceUntilIdle

fun provideScopeHolder(busScope: CoroutineScope, recorder: ScenarioRecorder? = null) = scopeHolder {
    scopeEmbedded("Todos") {
        config {
            createEventBus {
                coroutineScope { busScope }                       // share one TestDispatcher - see below
                if (recorder != null) recordInto(recorder, "Todos")
            }
        }
        // ...containers/threads as usual...
    }
}

@Test
fun addingATodoCascadesAndUpdatesState() = runScenario(::provideScopeHolder) { holder, _, recorder ->
    val todos = holder.findOrLoad("Todos")
    todos + AddTodo("Buy milk")
    advanceUntilIdle()

    scenario(recorder) {
        scope("Todos") {
            expectEvents("AddTodo", "TodoAdded")                  // exact, ordered, per-scope trace
            expectState(todos.resolveOrThrow(), listOf(Todo(0, "Buy milk")))
        }
    }
}
```

`runScenario` handles the boilerplate: a fresh `ScenarioRecorder`, one `TestDispatcher`-backed
`CoroutineScope` shared by every scope under test, and closing the holder afterward. Two things
worth knowing about how this stays deterministic:

- **Every scope under test must share that one `TestDispatcher`** (`coroutineScope { busScope }`
  above) so `advanceUntilIdle()` can actually drive its dispatch to completion before assertions
  run - a scope left on its default `Dispatchers.Default` races real time instead.
- **The recorder is wired via `watcher { }`, not `EventBus.output`** - deliberately. Every scope
  loaded through a `ScopeHolder` already gets a second, real-thread collector attached to `output`
  internally (for `consume`-routing bookkeeping, §9), and its one-slot replay buffer can make a
  later emission block on that real collector's own scheduling - invisible to and unresolvable by
  `advanceUntilIdle()`. A `watcher { }` call is a plain synchronous function invoked inside the
  dispatch coroutine itself, so it has no such race. One consequence: `expectEvents` only sees
  events that actually matched a `thread<T>()` (same as any other `watcher { }`) - an orphaned
  event is invisible to it, same as it is to the static graph.
- Cross-scope ordering is deliberately not asserted by `expectEvents` - only order *within* one
  scope is guaranteed. To pin a cross-scope effect, assert the downstream scope's settled state
  with `expectState` instead of relying on event ordering between two scopes.

***

## Common scenarios

Quick "I want to do X" → "use Y" pointers into the walkthrough above.

- **The same CRUD logic for several independent lists/instances.** A parent template scope
  (`implements`, §5) holding the shared `threads { }`, with each concrete scope loaded using
  different `parameters` (e.g. a different cache key) so they don't share storage. See
  `TodoListBase`/`Work`/`Personal` in the sample.
- **A derived/filtered view without a shared mutable variable.** Composite state via
  `.transform` (§4) inside the *same* scope - e.g. a raw list plus a "hide completed" flag folded
  into a filtered view, recomputed automatically whenever either input changes.
- **Business logic that shouldn't know your UI exists.** A separate, headless scope that reacts
  to the same events the UI dispatches, keeps its own event-sourced state (no access to any other
  scope's containers), and reports its verdict back as a new event - routed with `consume` (§9)
  to just the scope(s) that need it, not broadcast everywhere. See `TodoDomain` in the sample: it
  counts `AddTodo` events and emits `AddLimitReached` once a threshold is crossed, without ever
  touching `Work`/`Personal`'s own todo lists.
- **A supporting scope that should just always be around.** `dependsOn` (§5) ties a background
  scope's lifecycle to whatever scope Compose actually mounts, so it loads/frees automatically
  without a composable needing to know it exists (`TodoDomain` again - it depends on `TodoTabs`).
- **Push/pop navigation with typed parameters per screen.** `navGraph`/`NavigationDestination`
  (§7); each destination is itself an event, so navigating is just dispatching one.
- **A small piece of UI (a counter, a badge) reused across several screens** without re-wiring
  `LocalScope`/`resolveOrThrow` at every call site. `createWidget` (§8), bound to one fixed scope
  name and container type.
- **State that needs to survive navigating away and back.** Mount the owning scope *above* the
  `NavGraph` composable (§7), not inside one destination's own screen - only the top of the nav
  stack is ever composed, so a scope scoped to a single destination is disposed the moment you
  navigate elsewhere.
- **Pinning down a scope's event-flow shape, or asserting what a real dispatch actually did.**
  `event-thread-test` (§10): the static `EventGraph`/`graph("Scope") { reaches(...) }` DSL for
  "what could happen" (orphans, cycles, cascade shape), or `runScenario`/`scenario(recorder) {
  scope(name) { expectEvents(...); expectState(...) } }` for "what actually happened" in one
  deterministic run.

