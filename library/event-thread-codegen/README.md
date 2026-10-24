# event-thread-codegen

Gradle plugin that generates **typed scopes** for Event Thread from a JSON schema: solid,
compile-time-checked classes as an alternative to the `scopeHolder { }` DSL. The generator owns
the structure - keys, scopes and their wiring, container and handler bases; you write only the
logic and bind it in your `DependencyProvider` (Koin, `DummyProvider`, ...).

## Setup

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}

// build.gradle.kts of a Kotlin Multiplatform / JVM / Android module
import ru.alexey.event.threads.codegen.source.ResourceConfigSource

plugins {
    kotlin("multiplatform")
    id("io.github.izzzgoy.event-thread.codegen") version "<event-thread version>"
}

eventThreadCodegen {
    packageName = "com.example.app"
    source<ResourceConfigSource> { path = "event-thread/scopes.json" }
}
```

The plugin generates into `build/generated/eventThread/kotlin`, adds that to `commonMain`
(multiplatform) or `main` (JVM/Android), and adds `io.github.izzzgoy:event-thread-core` (same
version as the plugin) to that source set's `api`. Opt out with `addCoreDependency = false`, or
pick another core version with `coreVersion = "..."`. Generation runs before compilation; run it
alone with `./gradlew generateTypedScopes`.

### Schema sources

Exactly one, chosen with `source<T> { }`:

| Source | Reads |
| --- | --- |
| `ResourceConfigSource` | `path` (default `event-thread/scopes.json`) from the source set's resources |
| `PlainConfigSource` | `scopes { register("Todo") { description = "..." } }` in the build script (names/descriptions only) |
| `UrlConfigSource` | `url` (`https://`, `file://`), fetched on every build |
| your own | any `abstract class : ConfigSource()` returning `schema(context): Provider<List<ScopeModel>>` |

A custom source should read files/URLs through Gradle providers (`ProviderFactory.fileContents`,
a `ValueSource`), so the configuration cache never replays a stale schema.

## The schema

A JSON array of scopes:

```json
[{
  "name": "Work",
  "description": "The Work todo list",
  "params": { "kind": "primitive", "value": "params", "typeKey": "org.example.ListParams" },
  "dependsOn": ["TodoDomain"],
  "consumes": ["org.example.AddLimitReached"],
  "derived": [
    { "state": { "kind": "primitive", "value": "visibleTodos", "typeKey": "org.example.VisibleTodos" },
      "from": [ <todos>, <showCompleted> ] }
  ],
  "handlers": [
    { "input": { "name": "org.example.AddTodo" },
      "output": { "kind": "data", "type": <todos> } },
    { "input": { "name": "org.example.SelectList" },
      "reads": [ <draft> ],
      "output": { "kind": "event", "event": { "name": "org.example.SetDraftText" }, "onMismatch": "Strict" } },
    { "input": { "name": "ru.alexey.event.threads.LifecycleEvents" },
      "output": { "kind": "none" } }
  ]
}]
```

### Scope

| Field | Meaning |
| --- | --- |
| `name` | Scope identity (`ScopeKey.identity`) - the same string the DSL / `ScopeHolder` use |
| `description` | KDoc and `Scope.description` |
| `params` | Load-time params type (`ScopeKey<P>`); absent = `Unit`. Each distinct params value is its own scope instance |
| `dependsOn` | Scopes loaded along with this one: spec scope names, or qualified names of existing `ScopeKey` objects. A scope with params can't be depended on |
| `consumes` | Events routed here when *another* scope dispatches them (by handler input name - the scope must handle it) |
| `derived` | Containers computed from others of this scope (`state` from `from`, in order) instead of written by a handler |
| `handlers` | See below. Several per event are fine; they run in declaration order |

### Handler

| Field | Meaning |
| --- | --- |
| `input` | The event handled. A qualified name references an existing class; a simple name with `args` defines one (generated) |
| `output` | `data` (`type`: the state it updates - its container) → `StateProcessor`; `event` → `EventCascader` (`outputs` fixed to that event, enforced; `onMismatch`: `Strict`/`Warning`/`Silent`); `none` → `SideEffect` |
| `reads` | Containers it reads but doesn't update, by state type → a read-only `ContainerSnapshot` |
| `name` | Base class name. Default `<Event><Kind>` (`AddTodoStateProcessor`); needed only when two handlers would collide |
| `privacy`, `override` | Registration metadata, as `thread<T> { privacy(...); override(...) }` |

### Types (`ArgDef`)

| `kind` | Fields |
| --- | --- |
| `primitive` | `typeKey` (`String`, `Int`, or a qualified name of an existing type), `value` (its field/arg/container name) |
| `generics` | `baseClassName` (`List`, `Map`, qualified...), `parameters`, optional `field` name |
| `created` | `name` - with `fields` it's a definition (generated `data class`), without it a reference; optional `field` name |

A state type identifies a container: spell the same state identically everywhere it's used (the
generator rejects two different types that would get the same container name). A container's
name is its state's field name (`value` / `field`), else derived from its type
(`List<todo>` → `TodoList`).

## What gets generated

Under the configured `packageName`:

| Package | Contents |
| --- | --- |
| `.scopes` | `<Name>ScopeKey : ScopeKey<P>`; `<Name>ScopeBindings` (one factory per container and handler) and `<Name>ScopeDependencyBindings` (it, from a DependencyProvider); `<Name>Scope` with its `wire()`; `<Name>ScopeFragment` (dependsOn/consumes); `ScopeHolderBuilder.register<Name>Scope()` / `register<Name>Scope(bindings)` |
| `.events` | Abstract handler bases, one `<Event>Handler.kt` per input event; each with a nested `Deps` (its container + snapshots) |
| `.containers` | Abstract `<State>Container`s; a derived one has `Deps` with its source containers |
| `.models` | Events and `created` types the schema defines |

Containers and handler bases are shared by every scope using the same state / the same handler -
two scopes with identical handlers get one set of bases.

## Your side: implementations and bindings

Every container and handler a scope needs is a member of its generated `<Name>ScopeBindings`
interface. Supply them one of two ways:

**Implement the interface** - completeness checked by the compiler: a container or handler added
to the schema is a new member, and the build fails until its implementation is written.

```kotlin
class WorkBindings(private val json: Json) : WorkScopeBindings {
    override fun todos(params: ListParams) = TodosImpl(params, json)
    override fun addTodoStateProcessor(deps: AddTodoStateProcessor.Deps, params: ListParams) = AddTodoImpl(deps)
    // ... every other member - the IDE lists what's missing
}

scopeHolder { registerWorkScope { params, provider -> WorkBindings(provider.get()) } }
```

**Or bind them in a DependencyProvider** - `registerWorkScope()` uses the generated
`WorkScopeDependencyBindings`, which resolves each member (a missing binding then fails the
scope's first load, not the build):

```kotlin
class TodosImpl(params: ListParams, json: Json) : TodosContainer() {
    override val source = cacheJsonResource(params.cacheKey, emptyList<Todo>(), json)
}
class AddTodoImpl(deps: Deps) : AddTodoStateProcessor(deps) {
    override suspend fun process(event: AddTodo, state: List<Todo>) = state + Todo(event.text)
}

val todoModule = module {
    factory<TodosContainer> { (params: ListParams) -> TodosImpl(params, get()) }
    factory<AddTodoStateProcessor> { (deps: AddTodoStateProcessor.Deps) -> AddTodoImpl(deps) }
    factory<EventBus>(named("Work")) { EventBusBuilder().apply { watcher { log(it) } }.build() }
}

val holder = scopeHolder {
    dependencyProvider(koin.asDependencyProvider())
    registerWorkScope()                      // the scope + its dependsOn/consumes
}
holder.load(WorkScopeKey, ListParams(cacheKey = "work"))
```

Binding conventions:

- **Bind as `factory`, not `single`** - every scope instance needs its own containers, handlers
  and bus (a cascader can belong to one scope only).
- **Parameters**: a binding receives its `Deps` first (if the base has one), then the scope's
  params (if not `Unit`). Koin binds them by position - `(deps)`, `(deps, params)`, or `(params)`;
  `DummyProvider` by type (`it.resolve<Deps>()`, `it.resolveOrNull<ListParams>()`).
- **Bus**: an `EventBus` bound under the scope's name as qualifier is that scope's bus (e.g. a
  main-thread bus, or a logging watcher); otherwise a default one.
- **Containers** take an optional `coroutineScope` for their pipeline:
  `TotalImpl : TotalContainer(CoroutineScope(Dispatchers.Main + SupervisorJob()))`.
- With the DependencyProvider path, a missing binding fails the scope's first load.

## Testing

Generated into the test source set (`commonTest` / `test`), with `event-thread-test` and
`kotlin-test` added to it - turn off with `generateTests = false`. Per scope:

**`<Name>ScopeContract`** - abstract; extend it with the implementation under test:

```kotlin
class WorkContractTest : WorkScopeContract() {
    override fun bindings() = WorkScopeDependencyBindings(testKoin.asDependencyProvider())
    override fun params() = ListParams("test")
}
```

Its tests check, against the schema: every binding is provided (a missing DependencyProvider
binding fails here, not at app start), every handler is registered, `dependsOn`/`consumes` are
registered, and every **example** holds. `Dispatchers.Main` runs on a test dispatcher (override
`replaceMainDispatcher` to opt out); add your own `@BeforeTest` for anything else the
implementation needs (a temp cache directory, ...).

**Examples** - optional per handler, each a test of *your* implementation, its containers stubbed
with the example's state (no I/O, no app wiring):

```json
{ "input": { "name": "org.example.ToggleTodo" }, "output": { "kind": "data", "type": <todos> },
  "examples": [{ "name": "marksDone",
    "given":  [{ "id": 1, "text": "milk", "done": false }],
    "event":  { "id": 1 },
    "expect": [{ "id": 1, "text": "milk", "done": true }] }] }
```

| Handler | Example fields |
| --- | --- |
| `data` | `given` (its state before), `event`, `expect` (its state after); `reads` |
| `event` | `reads` (`{ "<container>": state }` for each container it reads), `event`, `emits` (the cascaded events' args, in order) |
| `none` | - (nothing to check) |

Values are JSON, turned into Kotlin at generation - no `@Serializable` needed: a `created` type or
defined event is built from its declared fields; an existing class from a JSON object as
`Type(key = value)` (constructor parameter names must match), from a string as an enum entry;
`List`/`Set`/`Map` from arrays/objects. `event` omitted means an `object` event. Keep examples to
deterministic handlers (no random ids, clocks, ...).

**`<Name>ScopeProbe`** - the scope built from your bindings, with typed access to its containers:

```kotlin
@Test fun filtersDone() = runTest {
    val probe = WorkScopeProbe(ListParams("t"), bindings)
    probe.send(AddTodo("milk"))
    probe.await(probe.todos) { it.size == 1 }         // probe.todos: the TodosContainer under test
    probe.awaitEmitted<AddLimitReached>()              // probe.emitted: everything the scope processed
    probe.close()
}
```

## Schema checks

Before generating, the schema is verified as a whole - every problem reported at once:

- every `dependsOn` names a scope of the schema (or a qualified `ScopeKey`), never one with params;
- every `consumes` names an event the scope handles;
- every cascaded event reaches a handler - in its own scope, or in a scope that `consumes` it;
- names don't collide: containers (one state spelled two ways), handler bases (`name` resolves
  it), defined events/types, generated files.

Runtime behaviour - what an implementation actually does - is yours; the declared structure and
flow can't drift from the schema.

## Limits

- Navigation graphs, emitters and scope-template inheritance (`implements`) stay in the DSL - a
  holder can mix both (`registerWorkScope()` next to `navGraph(...)`).
- Initial state isn't in the schema - container bindings supply `source`.
