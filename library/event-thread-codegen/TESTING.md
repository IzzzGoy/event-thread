# Testing typed scopes

What `event-thread-codegen` generates for tests, what of it runs (and when), what each piece
checks - and what it doesn't. Describes the current behaviour exactly; nothing here is implied.

## In one minute

- Two things are generated per scope: a **contract** and a **probe**. Neither runs by itself.
- A **contract** is an abstract test class. Its tests run only in a subclass you write - one
  short class per scope, supplying the implementation under test.
- A **probe** is not a test. It's a helper for writing your own behaviour tests, and runs only
  when a test you write creates one.
- Schema **examples** become tests inside the contract - so they run with it.
- Behaviour is yours to test: the contract checks the implementation against the *schema*
  (structure, wiring, examples), not against what the app should do in general.

## Where it goes

| | |
| --- | --- |
| Output | `build/generated/eventThread/test`, package `<packageName>.scopes` |
| Source set | `commonTest` (multiplatform), `test` (JVM/Android) |
| Added dependencies | `io.github.izzzgoy:event-thread-test` (same version as core), `org.jetbrains.kotlin:kotlin-test` |
| Turn off | `eventThreadCodegen { generateTests = false }` - no test sources, no added dependencies |

Generated files carry `@file:Suppress("unused", ...)`: a probe (or adapter) for a scope no test
uses yet is expected, not a mistake.

## The contract - `<Name>ScopeContract`

An `abstract class` with `@Test` methods. **Nothing runs until you subclass it**; every
subclass runs all of its tests, as part of the normal test task (`:module:testDebugUnitTest`,
`jvmTest`, ...).

```kotlin
class WorkContractTest : WorkScopeContract() {
    override fun bindings() = WorkScopeDependencyBindings(testKoin.asDependencyProvider())
    override fun params() = ListParams(cacheKey = "test")   // only for scopes with params
}
```

You supply:

| Member | |
| --- | --- |
| `bindings()` | The implementation under test: `<Name>ScopeDependencyBindings(provider)` to test your DI setup, or your hand-written `<Name>ScopeBindings`. Called anew in every test - each test gets fresh containers and handlers. |
| `params()` | The params the scope is built with (scopes with params only). |
| `replaceMainDispatcher` | Open, default `true`: `Dispatchers.Main` is replaced by a test dispatcher before each test and reset after - for implementations pinned to Main. |

Anything else the implementation needs (a temp cache directory, a fake server...) goes in your
subclass's own `@BeforeTest`/`@AfterTest`.

### The tests it runs

| Test | Builds | Checks | Fails when |
| --- | --- | --- | --- |
| `everyBindingIsProvided` | the scope, from `bindings()`, and wires it - every container and handler factory is called once | every factory returns | a DependencyProvider binding is missing, or a factory/constructor throws |
| `handlersAreRegistered` | the scope | each event has exactly as many handlers registered on the live scope as the schema declares for it | a handler is missing (or extra) on the live scope |
| `eventFlowMatchesSchema` | the scope | per event, the events it cascades into on the live scope (from its registration metadata) are exactly the schema's; its `event-thread-test` event graph (`toEventGraph()`) reaches each of them (`assertReaches`) | the running scope's flow differs from the schema's |
| `relationsAreRegistered` | a `ScopeHolder` with `register<Name>Scope { bindings() }` - the scope isn't loaded | the holder's `dependsOn` for the scope equals the schema's; each `consumes` event is routed to it | the generated relations don't reach the holder |
| `<handler>_example_<name>` | for each schema example: **only that handler**, from `bindings()`, with its containers **stubbed** with the example's state | state handler: `expect` == its container after `handle(event)`; cascading handler: `emits` == `preview(event)` | your handler implementation's result differs from the example |

How an example test works, precisely:

- The handler's own container and every container it `reads` are replaced by stubs - anonymous
  subclasses of the generated container classes holding the example's `given`/`reads` value.
  **Your container implementations are not used** (no sources, no I/O, no transforms).
- The handler comes from `bindings()` with those stubs as its `Deps` (and `params()`) - for a
  handler with a schema `rule`, that's the generated `<Handler>Rule` unless your bindings replace
  it, so examples are the rule's tests.
- No scope, no bus, no other handlers, no holder: one handler, called once.
- State handler: `handler.handle(event)`, then the container's value is compared with `expect`.
- Cascading handler: `handler.preview(event)` - the events it would emit, nothing dispatched -
  is compared with `emits`.
- Request handler (see [REMOTE.md](REMOTE.md#examples-and-tests)): its container starts `Success(given)`
  (or `Idle`), its remote API is a **fake** - the endpoint called answers `respond` or throws `fail`,
  any other endpoint fails the test - and `handler.execute(event)` runs one request (no bus, no
  policy): the container must end `Success(respond)` (or `Failure` with that message, keeping
  `given`) and the returned events must be `emits`.
- Side effects have no examples (nothing observable to compare).

### What the contract does not check

- Behaviour beyond the examples - a handler without examples is only checked to exist.
  That holds for rules too: a rule compiles (names and types are checked), but what it computes
  is only checked by its examples.
- Container implementations: their initial state (`source`), derived containers' `transforms`,
  persistence - only that they can be constructed.
- Side effects' effects.
- Containers' `initial` defaults beyond being constructible - an example's `given` replaces the
  initial state, so examples never see it.
- Anything across scopes at runtime: `dependsOn` loading and `consumes` routing are only checked
  as *declared on the holder*, never exercised (no event is routed). `eventFlowMatchesSchema`
  covers one scope's own flow, not routing between scopes.
- That a cascader actually emits what it declares for a given input - only the declared flow (and,
  through examples, the inputs you give).
- Dispatch-time behaviour: ordering between events, concurrency, timing.
- Output contracts at runtime: a cascader's declared `outputs` are enforced when it runs in a
  scope; example tests use `preview`, which doesn't enforce them.

## The probe - `<Name>ScopeProbe`

A plain class, **not a test**: no `@Test` methods, never run by the test task on its own. A probe
does something only when a test you write creates one. Generating one per scope means one is
ready for any scope you want to test - an unused one costs nothing.

```kotlin
@Test fun hidingDoneTodos() = runTest {
    val probe = WorkScopeProbe(ListParams("t"), bindings)
    probe.send(AddTodo("milk"))
    val todos = probe.await(probe.todos) { it.size == 1 }
    probe.send(ToggleTodo(todos.single().id))
    probe.send(SetShowCompleted(false))
    probe.await(probe.visibleTodos) { it.items.isEmpty() }
    probe.close()
}
```

| Member | |
| --- | --- |
| `<Name>ScopeProbe(params, bindings)` | Builds the scope from **your real bindings** (containers included - sources, I/O, transforms all real) and wires it immediately. |
| `todos`, `visibleTodos`, ... | One property per container: the very instance the scope got from your bindings, typed - no lookups or casts. |
| `send(event)` | Dispatches into the scope. |
| `emitted` | `StateFlow<List<Event>>` - every event the scope processed since the probe was built: sent ones and everything they cascaded, in the order their handlers *finished* - a cascaded event can come before its cause. For order, use `scenario { expectEvents(...) }`. |
| `recorder`, `scenario { }` | The same events in a `ScenarioRecorder`, for `event-thread-test`'s scenario DSL: `probe.scenario { expectEvents("AddTodo", "AddLimitReached"); expectState(probe.todos, "one todo") { it.size == 1 } }`. Recorded in the order the scope *received* them - causal: an event always comes before what it cascades. Await first - it reflects only what was received so far. |
| `await(state) { }` | Waits for a state to satisfy the predicate, returns it (default timeout 5 s). |
| `awaitEmitted<E> { }` | Waits for a processed event of type `E` matching the predicate, returns it. |
| `scope` | The live scope itself. |
| `close()` | Closes the scope and stops recording - call it at the end of every test. |

Behaviour to know:

- **One scope, no holder.** `dependsOn` scopes aren't loaded and nothing is routed in or out
  (`consumes`); events another scope would send must be sent with `send` yourself.
- **Waits are real time.** The scope's bus runs on real dispatchers, which `runTest`'s virtual
  clock doesn't drive - `await`/`awaitEmitted` wait on `Dispatchers.Default` with a real timeout.
  Don't use `advanceUntilIdle`/`delay` to wait for the scope.
- **Different events are processed concurrently** by the bus. If order matters, `await` the effect
  of one event before sending the next.
- `Dispatchers.Main` isn't replaced by the probe: if your implementation uses Main, set it in the
  test (`Dispatchers.setMain(...)`) - the contract does this, the probe doesn't.

The probe is built on `TypedScopeProbe` from `event-thread-test` - extend that directly for a
hand-written typed scope.

## What runs, in short

| | Runs when |
| --- | --- |
| Contract tests (including examples) | a concrete subclass of the contract exists in a test source set |
| Probe | a test creates it |
| Nothing else | - generated test support is never run on its own |

In the sample app, `:composeApp:testDebugUnitTest` runs the six contract subclasses
(`TypedScopeContractTests.kt` in `commonTest`, `TypedListContractTests.kt` in `androidUnitTest` -
Work/Personal need a temp cache directory) and two probe tests: `WorkScopeProbeTest`, and
`TypedSuggestionsTest` (the generated Ktor API against a `MockEngine` fake server); the other four
probes are generated but unused.

## Platforms

- Run and verified: JVM and Android unit tests, and the iOS simulator (Kotlin/Native) - the
  contract's inherited `@Test` methods execute there too (CI runs `iosSimulatorArm64Test`).
- Not verified: JS.

## Relation to event-thread-test

Generated tests are built on `event-thread-test`:

- the contract's `eventFlowMatchesSchema` uses its event graph (`toEventGraph()`, `assertReaches`);
- probes are `TypedScopeProbe`s and record into its `ScenarioRecorder`, so its scenario DSL
  (`expectEvents`, `expectState`) works on them (`probe.scenario { }`);
- `kotlinx-coroutines-test` (`runTest`, `setMain`) comes through it.

Its other tools (`assertNoCycles`, `assertNoOrphanEvents`, the `graph { reaches(...) }` DSL, a
holder-wide graph from `generateActiveSchema().toEventGraph()`) remain available for hand-written
tests.
