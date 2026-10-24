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

A versioned document:

```json
{
  "$schema": "https://raw.githubusercontent.com/IzzzGoy/event-thread/main/library/event-thread-codegen/scopes.schema.json",
  "format": "1.0",
  "version": "1.0.0",
  "scopes": [ ... ]
}
```

`$schema` points editors at [`scopes.schema.json`](scopes.schema.json) (completion, inline
errors - also what an AI agent should validate against); `format` and `version` are explained in
[Versioning](#versioning). A bare array of scopes (the 1.1 format) still reads, as format 1.0
without a version.

A scope:

```json
{
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
}
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
`kotlin-test` added - off with `generateTests = false`. Per scope:

- **`<Name>ScopeContract`** - an abstract test class; **runs only in a subclass you write**
  (supplying `bindings()` and `params()`). Checks every binding is provided, handlers and
  relations are registered, and every schema **example** holds against your handler
  implementation (containers stubbed with the example's state).
- **`<Name>ScopeProbe`** - **not a test**: a helper for your own behaviour tests (the scope built
  from your real bindings, typed containers, `send`/`await`/`emitted`); runs only when a test
  creates one.

Examples are written per handler in the schema:

```json
"examples": [{ "name": "marksDone",
  "given":  [{ "id": 1, "text": "milk", "done": false }],
  "event":  { "id": 1 },
  "expect": [{ "id": 1, "text": "milk", "done": true }] }]
```

`given`/`expect` for state handlers, `reads` + `emits` for cascading ones; values are JSON turned
into Kotlin at generation (no `@Serializable` needed - constructor parameter names must match the
keys). Keep examples to deterministic handlers.

**[TESTING.md](TESTING.md)** describes exactly what runs when, what each test checks and doesn't,
and how the probe behaves.

## Versioning

Two versions, two meanings:

- **`format`** - the schema *language* (`major.minor`). A plugin reads any format with its own
  major and a minor up to its own; a newer one fails with "update event-thread-codegen" (it may use
  fields this plugin doesn't know). Additions to the language bump the minor and stay optional; a
  major format change comes with a major plugin release.
- **`version`** - the semver of *your* contract. With a `baseline` - the schema as last released -
  `checkScopesCompatibility` (part of `check`) classifies every change and fails if `version`
  isn't bumped enough:

  | Bump | Changes |
  | --- | --- |
  | MAJOR | anything existing removed or changed in meaning: a scope, handler, container or relation removed; a state, params, event or output type changed; a handler's `reads` changed (its `Deps`); `override`/`onMismatch` changed |
  | MINOR | additions: scopes, handlers, containers (derived too), `dependsOn`/`consumes` entries. Regenerating adds bindings members - implementations must be extended, the compiler lists what |
  | PATCH | no generated signature or behaviour changes: descriptions, `privacy`, examples |

  In `0.y.z` (semver's initial development) MAJOR changes need a minor bump, the rest a patch.

  ```kotlin
  eventThreadCodegen {
      baseline = layout.projectDirectory.file("event-thread/scopes.baseline.json")
  }
  ```

  Keep the baseline outside resources (it would be packaged) and refresh it at each release; the
  classification is written to `build/reports/eventThread/compatibility.txt`.

Generated code records both: every file's header names the plugin, schema `version` and `format`
it came from, and a generated `ScopesSchema` object (`.scopes`) carries `VERSION`/`FORMAT` for a
runtime check or log line.

## Reports

Next to the generated code, in `build/reports/eventThread`:

- `scopes.md` - the event flow as a Mermaid diagram: per scope its events, containers, cascades,
  reads and derived state; routing (`consumes`) and `dependsOn` between scopes.
- `tasks/<Scope>.md` - what an implementation must provide: every container and handler binding
  with its event, state, reads and the schema's examples, and the contract that checks it. A
  checklist for a developer; a self-contained brief for an AI agent ("implement
  `tasks/Work.md`").
- `scopes.schema.json` - the format's JSON Schema, for offline editor setup.

## Schema checks

Before generating, the schema is verified as a whole - every problem reported at once:

- every `dependsOn` names a scope of the schema (or a qualified `ScopeKey`), never one with params;
- every `consumes` names an event the scope handles;
- every cascaded event reaches a handler - in its own scope, or in a scope that `consumes` it;
- names don't collide: containers (one state spelled two ways), handler bases (`name` resolves
  it), defined events/types, generated files;
- **cascade loops** (an event cascading, through its own or consuming scopes, back to itself) are
  warnings - a loop guarded by the implementation's own condition is legitimate. With
  `strictSchema = true` warnings fail the build too.

Runtime behaviour - what an implementation actually does - is yours; the declared structure and
flow can't drift from the schema.

## Limits

- Navigation graphs, emitters and scope-template inheritance (`implements`) stay in the DSL - a
  holder can mix both (`registerWorkScope()` next to `navGraph(...)`).
- Initial state isn't in the schema - container bindings supply `source`.
