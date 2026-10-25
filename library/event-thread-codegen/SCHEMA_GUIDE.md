# Writing a scope schema

How to design and write `scopes.json` - for developers, and (in [For AI agents](#for-ai-agents))
as a strict procedure for agents. Every complete schema in this guide is checked by the plugin's
tests: it parses and generates.

Reference material: [README.md](README.md) (field reference, generated code),
[TESTING.md](TESTING.md) (examples, contracts), [`scopes.schema.json`](scopes.schema.json) (the
format as JSON Schema - validate against it).

---

## 1. The model in five minutes

A schema describes **what** each scope is - its state, the events it reacts to, how it reacts,
how scopes relate. The generator turns it into contracts (keys, scopes, wiring, container and
handler bases); you write only the logic.

| Concept | In the schema | Becomes |
| --- | --- | --- |
| **Scope** | `{ "name": "Work", ... }` | `WorkScopeKey`, `WorkScope`, `WorkScopeBindings` |
| **State** (a container) | a *state type* - the `type` of a `data` output, or a `derived` state | `<State>Container` |
| **Event** | a handler's `input` (and a cascade's `event`) | your existing class, or a generated `data class` |
| **Handler** | `{ "input": ..., "output": ... }` | an abstract handler base you implement |
| **Relation** | `dependsOn`, `consumes` | a fragment registered with the scope |
| **Example** | `examples` on a handler | a generated test of your implementation |
| **Remote API** | the document's `remote` | `<Name>RemoteApi` (+ a Ktor implementation) |

The three handler kinds - pick by **what the handler produces**:

| It produces... | `output` | Base class | You implement |
| --- | --- | --- | --- |
| a new value of one piece of the scope's state | `{ "kind": "data", "type": <state> }` | `StateProcessor` | `process(event, state): State` |
| other events | `{ "kind": "event", "event": <event> }` | `EventCascader` | `cascade(input): List<Event>` |
| nothing observable (logging, analytics, navigation call) | `{ "kind": "none" }` | `SideEffect` | `handle(event)` |
| the result of a remote call - loading, then data or an error | `{ "kind": "request", "call": "<Api>.<endpoint>", "into": <RemoteData<R>>, ... }` | `Request` | nothing - generated (§6) |

A **state type identifies a container**: every handler whose `data` output has the same type
(spelled identically) updates the *same* container. Different state = different type spelling
(usually a different `value`/`field` name).

---

## 2. Workflow

1. **List the scopes.** One per screen / feature / long-lived unit of state; a headless one for
   business rules that only react to events.
2. **For each scope, list its state** - the containers. Name each (`todos`, `draft`,
   `showCompleted`). Decide which are computed from others (`derived`).
3. **List the events each scope handles** and, per event, what it does: updates which state
   (`data`), emits which events (`event`), or only a side effect (`none`). One event may have
   several handlers (they run in order).
4. **Decide relations**: which scope must be loaded with another (`dependsOn`), which events a
   scope receives from *other* scopes (`consumes`).
5. **Write the document** (§3), validate it in the editor through `$schema`.
6. **Write rules** for handlers whose behaviour is a plain function of the event and state (§5) -
   they need no code. Declare remote calls as `remote` APIs and `request` handlers (§6) - no code
   either.
7. **Generate**: `./gradlew generateTypedScopes`. Fix reported problems - all are listed at once.
8. **Add examples** to handlers whose result is deterministic (§7).
9. **Implement** the rest - `build/reports/eventThread/tasks/<Scope>.md` lists every binding to write.
10. **Test** - extend `<Name>ScopeContract` (see [TESTING.md](TESTING.md)).
11. **Version** - bump `version` as the changes require (§10).

---

## 3. A complete document

```json
{
  "$schema": "https://raw.githubusercontent.com/IzzzGoy/event-thread/main/library/event-thread-codegen/scopes.schema.json",
  "format": "1.1",
  "version": "1.0.0",
  "scopes": [
    {
      "name": "Counter",
      "description": "A counter with a step and a milestone rule",
      "params": {
        "kind": "created", "name": "CounterParams",
        "fields": [{ "kind": "primitive", "value": "step", "typeKey": "Int" }]
      },
      "initial": { "count": "0" },
      "handlers": [
        {
          "input": { "name": "Tick" },
          "output": { "kind": "data", "type": { "kind": "primitive", "value": "count", "typeKey": "Int" } },
          "rule": "state + params.step",
          "examples": [{ "name": "addsStep", "given": 4, "event": {}, "expect": 5 }]
        },
        {
          "input": { "name": "Tick" },
          "reads": [{ "kind": "primitive", "value": "count", "typeKey": "Int" }],
          "output": {
            "kind": "event",
            "event": { "name": "Milestone", "args": [{ "kind": "primitive", "value": "at", "typeKey": "Int" }] }
          },
          "rule": "when count % 10 == 0 emit Milestone(at: count)",
          "examples": [
            { "name": "at10", "reads": { "count": 10 }, "emits": [{ "at": 10 }] },
            { "name": "at7", "reads": { "count": 7 }, "emits": [] }
          ]
        },
        { "input": { "name": "Milestone", "args": [{ "kind": "primitive", "value": "at", "typeKey": "Int" }] }, "output": { "kind": "none" } }
      ]
    }
  ]
}
```

What this generates: `CounterScope(params, bindings)`, a `CounterParams` data class, events `Tick`
(`data object`) and `Milestone(at: Int)`, a `CountContainer`, handler bases
`TickStateProcessor`, `TickEventCascader`, `MilestoneSideEffect`, and a contract with three
example tests. Both `Tick` handlers have a `rule`, so their implementations are generated too
(`TickStateProcessorRule`, `TickEventCascaderRule`), and `count` starts from its `initial` (`CountContainerInitial(0)`):
only `MilestoneSideEffect` is left to write.

> The `addsStep` example assumes a step of 1 - examples run with your contract's `params()`
> (here: through the rule's `params.step`).
> Keep examples independent of params, or choose `params()` to match.

---

## 4. Constraints - enforced by the plugin

Breaking any of these fails generation with the message shown (all problems are listed at once).

### Document

| Rule | Fails with |
| --- | --- |
| `format` must be `1.x` with `x` ≤ the plugin's | `... is newer than this plugin understands ... update event-thread-codegen` |
| `version`, if present, is semver `major.minor.patch[-pre]` | `Schema version '...' isn't semver` |
| A document using `rule` declares `"format": "1.1"` (or later) | `Handler rules need "format": "1.1"` |
| A document using `remote`/`request` declares `"format": "1.2"` | `Remote APIs and request handlers need "format": "1.2"` |
| No unknown fields anywhere (the parser is strict) | a serialization error naming the field |
| `kind` values are exact: `primitive`/`generics`/`created`, `data`/`event`/`none` | a serialization error |

### Names and types

| Rule | Fails with |
| --- | --- |
| Scope, event (simple), created type, field, handler `name`: Kotlin identifiers (`[A-Za-z_][A-Za-z0-9_]*`) | `... is not a valid Kotlin identifier` |
| A `typeKey`/`baseClassName` is a builtin, a simple name of a type in the generated `models` package, or a **qualified** name | `... is not a valid Kotlin type name` |
| `generics` has at least one parameter | `Generic '...' needs at least one type parameter` |
| A **qualified** event name references an existing class and has **no `args`** | `Event '...' references an existing class - it can't declare args` |
| A name defined in several places is defined identically (events, created types) | `Event/Type '...' is defined differently in different places` |
| A field of a generated class (event arg, created field) has a name: `value` for primitives, `field` for generics/created | `'...' field #N ... has no name - set its field` |

### State and handlers

| Rule | Fails with |
| --- | --- |
| The same state is spelled identically everywhere (container names come from it) | `Different state types share a container name [...]` |
| `reads` and `derived.from` name containers of the same scope (declared before, for `derived`) | `... reads [...], which is no container of the scope` |
| A state is either written by handlers or `derived`, not both (in any scope) | `... is written by a handler, so it can't also be derived` |
| Two handlers of the same kind for one event need distinct `name`s | `Handler base ... is defined differently ... or give one of them a name` |
| `consumes` lists only events the scope handles | `Scope '...' consumes [...] but has no handler for it` |
| `dependsOn` names a scope of the schema (or a qualified `ScopeKey`) without `params` | `... dependsOn '...', which no spec declares` / `which needs params` |

### Flow

| Rule | Fails with |
| --- | --- |
| Every cascaded event reaches a handler: in its own scope, or in a scope that `consumes` it | `X, cascaded by 'S' on E, has no handler in any scope` / `never reaches a handler: [...] handle it but don't consume it` |
| Cascade loops (A → B → A) are **warnings**; errors with `strictSchema = true` | `Cascade loop: ...` |

### Examples

| Rule | Fails with |
| --- | --- |
| `data` handler: `given` and `expect` | `a state handler's example needs given and expect` |
| `event` handler: `emits` | `a cascading handler's example needs emits` |
| `none` handler: no examples | `a side effect has no output to check` |
| `reads` keys are the container names of the handler's `reads`, and all of them are given | `reads [...] aren't containers this handler reads` / `needs reads.<name>` |
| A JSON object only for a type whose fields are known (created, defined event) or an existing class at the top level | `can't build an object whose type isn't in the schema` |

---

## 5. Rules - behaviour in the schema

The essentials; the complete language (every operator and stage, the generated Kotlin, a
cookbook, the grammar) is in [RULES.md](RULES.md).

A handler's `rule` is its behaviour in a small pipeline language - for the many handlers that
are a plain function of the event and the state ("append", "remove by id", "toggle", "emit when
over a limit"). A handler with a rule needs no code: the generator compiles the rule to a
`<Handler>Rule` class and makes it the binding's **default**.

```text
"rule": "state | remove where it.id == event.id"                           (data: the new state)
"rule": "state | update where it.id == event.id set (done: not it.done)"
"rule": "state + Todo(id: event.id, text: event.text, done: false)"
"rule": "when totalAdded >= 5 emit AddLimitReached(totalAdded)"            (event: what to emit)
```

**Use a rule** when the result depends only on the event, the handler's own state, its `reads`
and the scope's `params`. **Write code** for anything else - random ids, clocks, I/O, logging,
calls into other code (side effects can't have rules).

### Names

| Name | Is | In |
| --- | --- | --- |
| `event` | the input event - `event.text` | every rule |
| `state` - or the container's name (`todos`) | the handler's own state, before the event | `data` rules |
| a `reads` container's name (`totalAdded`) | that container's current value | handlers with `reads` |
| `params` | the scope instance's params - `params.limit` | scopes with `params` |
| `it` | the current element | inside a pipeline stage |
| a type's simple name (`Todo`, `AddLimitReached`) | a construction `Todo(...)`, or an `object` | types and events of the schema |

### Values and operators

| Write | Means (Kotlin) |
| --- | --- |
| `1`, `2.5`, `"text"`, `true`, `false`, `null` | literals |
| `[a, b]`, `[]` | `listOf(a, b)`, `emptyList()` |
| `x.field` | field access |
| `Todo(id: 1, text: "a")`, `Milestone(count)` | construction - named or positional arguments |
| `x with (done: true)` | `x.copy(done = true)` |
| `+ - * / %` | arithmetic; on lists `+` appends (an element or a list), `-` removes |
| `== != < <= > >=` | comparison |
| `and` `or` `not` (or `&&` `\|\|` `!`) | logic |
| `a ?: b` | `b` when `a` is null |
| `if c then a else b` | `if (c) a else b` |
| `( ... )` | grouping |

### Pipelines

`list | stage | stage ...` - each stage takes the list on its left; per-element expressions see
the element as `it`.

| Stage | Result |
| --- | --- |
| `where <cond>` / `remove where <cond>` | the elements matching / not matching |
| `select <expr>` | each element mapped |
| `update where <cond> set (field: value, ...)` | matching elements copied with new fields, others as they were |
| `count` / `count <cond>` | how many (matching) |
| `any <cond>` / `all <cond>` / `none <cond>` | a Boolean |
| `sum` / `sum <expr>` | the sum (of the expression) |
| `first` / `last` (optionally `<cond>`) | that element, or `null` - pair with `?:` |
| `sortBy <expr>` / `sortByDescending <expr>` / `sorted` | sorted |
| `distinct`, `take <n>`, `drop <n>` | as in Kotlin |

A pipeline binds loosest: `state | count it.done` is a whole rule; to use a pipeline's result in
an expression, parenthesize it - `(state | count) > 3`. Stage words are only stage words after
`|` - a container called `count` is still usable by name.

### Cascading rules

An `event` handler's rule is one or more clauses separated by `;`, each emitting when its
condition holds:

```text
when totalAdded >= 5 emit AddLimitReached(totalAdded)
emit Saved(event.id); when (state | count) > 100 emit Full(100)
```

`when` is optional - `emit X(...)` alone always emits. Several events in one clause:
`emit Logged(1), Logged(2)` - all of the handler's one declared output type.

### Initial values

A scope's `initial` gives containers their starting state, by container name - expressions over
`params` and literals:

```text
"initial": { "showCompleted": "true", "limitHits": "0", "selectedList": "'Work'", "draft": "params.initialText" }
```

Generates `<State>ContainerInitial(...)` as the container binding's default. Not for derived
containers, or state that comes from storage or must run on a specific dispatcher - bind a
container for those.

Strings: write `'text'` - `"text"` inside a JSON string needs escaping (`\"text\"`).

### What's checked

At generation, with the position in the rule (all problems listed at once):

| Rule | Fails with |
| --- | --- |
| Syntax | `Unexpected ...` / `Expected ...`, with a `^` under the column |
| Every name is one of those above | `Unknown name 'x' - this rule can use: event, state, ...` |
| `it` only in a stage; `state` only in a `data` rule; `params` only with params | `` `it` is only defined inside a pipeline stage `` / `` `state` is a state handler's own state `` / `This scope has no params` |
| Constructed types exist in the schema | `Unknown type 'X' - the schema's types: ...` |
| A `data` rule is an expression; an `event` rule is `emit` clauses | `A state handler's rule is an expression ...` / `A cascading handler's rule emits events ...` |
| A cascading rule emits only its declared output | `This handler's output is X - it can't emit Y` |
| No rule on a `none` handler | `a side effect can't have a rule` |
| `initial` only for a scope's handler-written containers, without `event`/`state` | `no such container` / `a derived container's state comes from its transforms` / `There's no event here` |
| A rule over 120 characters | a warning - `consider writing it as code` |
| A rule shared by scopes with different params types doesn't use `params` | `... have different params types` |

**Types** are checked by the Kotlin compiler when the generated `...Rule` class compiles - the
class's KDoc quotes the rule, so an error there points straight back to it.

### Rules and code together

The generated binding is a default, not a lock:

```kotlin
interface WorkScopeBindings {
    fun deleteTodoStateProcessor(deps: DeleteTodoStateProcessor.Deps, params: ListParams): DeleteTodoStateProcessor =
        DeleteTodoStateProcessorRule(deps)          // generated default
}
```

- `<Name>ScopeDependencyBindings` uses an implementation bound in the DependencyProvider if there
  is one, and the rule otherwise.
- A hand-written `<Name>ScopeBindings` overrides the member, or keeps the default.
- Examples test whichever implementation is in use - the rule, unless you replaced it.

---

## 6. Remote data - requests

Calls to a server are declared too: the document's `remote` lists APIs and their endpoints, and a
handler with a `request` output calls one, tracking it in a `RemoteData` container
(`Idle` / `Loading` / `Success` / `Failure`, keeping the last value) and cascading events after.
All of it is generated - the API interface, a Ktor implementation, the handler, the container -
and every piece is an overridable default. The complete reference: [REMOTE.md](REMOTE.md).

```json
{
  "$schema": "https://raw.githubusercontent.com/IzzzGoy/event-thread/main/library/event-thread-codegen/scopes.schema.json",
  "format": "1.2",
  "version": "1.0.0",
  "remote": [
    {
      "name": "Weather",
      "endpoints": [
        {
          "name": "forecast", "method": "GET", "path": "cities/{city}/forecast",
          "args": [
            { "kind": "primitive", "value": "city", "typeKey": "String" },
            { "kind": "primitive", "value": "days", "typeKey": "Int" }
          ],
          "response": { "kind": "created", "name": "Forecast", "fields": [
            { "kind": "primitive", "value": "city", "typeKey": "String" },
            { "kind": "generics", "baseClassName": "List", "field": "temperatures", "parameters": [ { "kind": "primitive", "value": "t", "typeKey": "Double" } ] }
          ] }
        }
      ]
    }
  ],
  "scopes": [
    {
      "name": "CityWeather",
      "params": { "kind": "created", "name": "CityParams", "fields": [ { "kind": "primitive", "value": "city", "typeKey": "String" } ] },
      "initial": { "failures": "0" },
      "handlers": [
        {
          "input": { "name": "LoadForecast", "args": [ { "kind": "primitive", "value": "days", "typeKey": "Int" } ] },
          "output": {
            "kind": "request",
            "call": "Weather.forecast",
            "args": { "city": "params.city", "days": "event.days" },
            "into": { "kind": "generics", "baseClassName": "RemoteData", "field": "forecast", "parameters": [ { "kind": "created", "name": "Forecast" } ] },
            "emits": [ { "name": "ForecastFailed", "args": [ { "kind": "primitive", "value": "reason", "typeKey": "String" } ] } ],
            "onFailure": "emit ForecastFailed(error.message ?: 'unknown')",
            "policy": { "concurrency": "latest", "retry": 1, "timeoutMs": 10000 }
          },
          "examples": [
            { "name": "loads", "event": { "days": 2 }, "respond": { "city": "Oslo", "temperatures": [ 3.5, 4.0 ] }, "emits": [] },
            { "name": "offline", "event": { "days": 2 }, "fail": "offline", "emits": [ { "reason": "offline" } ] }
          ]
        },
        {
          "input": { "name": "ForecastFailed", "args": [ { "kind": "primitive", "value": "reason", "typeKey": "String" } ] },
          "output": { "kind": "data", "type": { "kind": "primitive", "value": "failures", "typeKey": "Int" } },
          "rule": "state + 1"
        }
      ]
    }
  ]
}
```

Generated: `WeatherRemoteApi` + `WeatherRemoteApiKtor`, a `@Serializable` `Forecast`,
`LoadForecastRequest` + `LoadForecastRequestRule`, the `forecast: RemoteData<Forecast>` container
(starting `Idle`). The app binds one `HttpClient` (base URL, JSON, auth) - nothing else.

| Request field | |
| --- | --- |
| `call` | `"<Api>.<endpoint>"` |
| `args` | every endpoint arg, as a rule expression (`event`, `params`, `reads`) |
| `into` | `RemoteData<R>` of the endpoint's response `R` - the container |
| `emits` / `onSuccess` / `onFailure` | events it may cascade; cascading rules after success (sees `result`) / failure (sees `error`) |
| `policy` | `concurrency` (`latest` / `drop` / `queue`), `retry`, `retryDelayMs`, `timeoutMs`, `debounceMs` |

**Choose `concurrency`** by what a second trigger means: a newer request (`latest` - refresh,
search), a duplicate (`drop` - "load more", double taps), or more work (`queue` - saves).

Request examples run against a fake API: `respond` (the response) or `fail` (an error message),
optionally `given` (the last loaded value); the container must end `Success(respond)` or
`Failure` keeping `given`, and the events must be `emits`.

---

## 7. Conventions - not enforced, but follow them

**Naming**

- Scopes: nouns, `UpperCamel` - `Work`, `TodoDomain`, `Checkout`. Don't suffix `Scope` (it's
  added: `Work` → `WorkScope`).
- Events: what happened or what's asked, `UpperCamel` - `AddTodo`, `TodoAdded`, `SelectList`.
- State: name it by meaning with `value`/`field` - `todos`, `draft`, `selectedList` - never by
  type (`list`, `string`). The name becomes the container (`todos` → `TodosContainer`).
- Handler `name`: only when needed (two of the same kind for one event); say what it does -
  `CountAddedTodos`, `ClearDraftOnAdd`.

**Referencing vs defining**

| You have... | Write | Generated |
| --- | --- | --- |
| an existing event class | `"input": { "name": "com.example.AddTodo" }` (qualified, no `args`) | nothing - it's used |
| an event to define | `"input": { "name": "AddTodo", "args": [...] }` (simple) | `data class AddTodo(...)` (`data object` without args) |
| an existing type | `{ "kind": "primitive", "value": "<name>", "typeKey": "com.example.Todo" }` | nothing |
| a type to define | `{ "kind": "created", "name": "Todo", "fields": [...] }` | `data class Todo(...)` in `models` |
| a type defined elsewhere in the schema | `{ "kind": "created", "name": "Todo" }` (no fields) | nothing more |

In an existing app, reference its classes (qualified names). In a new feature, defining them in
the schema keeps the contract self-contained.

**State**

- One container per independent piece of state; don't bundle unrelated values into one object.
- Write `List<Todo>` the same way everywhere - typically
  `{ "kind": "generics", "baseClassName": "List", "field": "todos", "parameters": [ <Todo> ] }`.
- A filtered/sorted/combined view of other state is `derived`, never written by a handler.
- Params (`params`) identify an *instance* (which list, which item) - not configuration you could
  inject. Prefer a `created` type: it's a `data class`, so equal params mean the same instance.

**Handlers**

- One responsibility each. Updating two pieces of state = two `data` handlers for the event.
- Business rules that only observe and react go in their own headless scope, reporting back with
  events (`event` output) that the UI scopes `consumes`.
- A cascader reading state uses `reads` (a read-only snapshot) - never another scope's state.

**Relations**

- `consumes` for events *another scope* emits (or that UI code dispatches and only these scopes
  should get). An event handled where it's emitted needs no `consumes`.
- `dependsOn` for a scope that must exist whenever this one does (a domain scope behind a screen).
  The target can't have params.

**Rules**

- Prefer a rule for every handler it can express - less code, and the behaviour is visible in
  the schema. Keep each one short; when it needs more than a line, write code.
- Add examples to ruled handlers too - they're the rule's tests.

**Examples**

- Only for deterministic handlers - no random ids, clocks, I/O.
- Small and specific: one behaviour per example, named for it (`marksDone`, `belowLimitIsQuiet`).
- Cover the edges: the boundary of a rule (`at10` / `at7`), the no-op case.

---

## 8. Recipes

**A list per params** - same scope, one instance per list:

```text
"params": { "kind": "created", "name": "ListParams", "fields": [{ "kind": "primitive", "value": "listId", "typeKey": "String" }] }
```

Load with `holder.load(ListScopeKey, ListParams("work"))` - each `listId` is its own instance.

**A derived view:**

```text
"derived": [{ "state": <visibleTodos>, "from": [<todos>, <showCompleted>] }]
```

Implement it as a container with `transforms` over the two sources (its `Deps` hand them over).

**A domain rule reporting back** (a headless scope + routing):

```text
TodoDomain:  AddTodo -> data totalAdded;  AddTodo (reads totalAdded) -> event AddLimitReached
Work:        consumes AddLimitReached;    AddLimitReached -> data limitHits
TodoTabs:    dependsOn TodoDomain         (keeps the domain loaded while the app runs)
```

**Several handlers for one event** - they run in declaration order; the second sees the first's
write:

```text
{ "input": AddTodo, "name": "CountAddedTodos", "output": data totalAdded }
{ "input": AddTodo, "reads": [totalAdded], "output": event AddLimitReached }
```

---

## 9. Anti-patterns

| Don't | Why | Do |
| --- | --- | --- |
| Spell the same state two ways (`List<Todo>` with fields once, without elsewhere) | two containers - or a clash error | one spelling, copied |
| Write a derived view from a handler | it drifts from its sources | `derived` |
| Read another scope's state | scopes are isolated | send an event; `consumes` it |
| One handler updating "everything" | can't be tested or reused | one `data` handler per state |
| `consumes` an event emitted in the same scope | routing skips the emitting scope's key | handle it directly |
| Examples for handlers with random/time output | flaky tests | examples only for deterministic ones |
| Params for injectable configuration | creates needless instances | inject through bindings |
| Bump `version` arbitrarily | the compatibility check enforces meaning | follow §10 |

---

## 10. Versioning

- `format` - the schema-language version: `1.0`; `1.1` for `rule`s and `initial`s; `1.2` for
  `remote` APIs and `request` handlers. Use the lowest that has
  what you need; a plugin older than the format rejects the document with "update".
- `version` - semver of *your contract*, checked against a `baseline` (the schema as last
  released) by `checkScopesCompatibility`:

| Bump | When |
| --- | --- |
| MAJOR | something removed or changed in meaning: a scope, handler, container or relation removed; a state, params, event or output type changed; a handler's `reads` changed; `override`/`onMismatch` changed; a `rule` or `initial` removed (bindings relying on it stop compiling); an endpoint removed or its args/response changed; a request's `call`/`into`/`emits` changed |
| MINOR | something added: scopes, handlers, containers, `dependsOn`/`consumes` entries, a `rule` or `initial`, a remote API or endpoint |
| PATCH | only descriptions, `privacy`, examples, a changed `rule` or `initial`, an endpoint's HTTP mapping, a request's `args`/`onSuccess`/`onFailure`/`policy` |

Before `1.0.0`, a MAJOR change needs a minor bump and everything else a patch. At each release,
copy the schema to the baseline file.

---

## For AI agents

Follow this procedure exactly. The plugin is strict: an invented field, a misspelled `kind`, or
an inconsistent state fails the build.

### Output contract

- Output **one JSON document** - no comments, no trailing commas, no Markdown inside.
- Root: `{ "$schema": "<the URL in §3>", "format": "1.2", "version": "<semver>", "scopes": [...], "remote": [...] }`
  (`remote` only when the feature calls a server).
- Use **only** the fields in [`scopes.schema.json`](scopes.schema.json). Never add others.
- When modifying an existing schema: keep everything not asked to change byte-identical, and
  bump `version` per §10.

### Procedure

1. Read the requirements; list scopes, their state, events, handlers, relations (§2 steps 1-4).
2. Decide for every event: existing class (qualified name, no `args`) or new (simple name +
   `args`). Same for every type: existing (`primitive` with qualified `typeKey`) or new
   (`created` with `fields`).
3. Choose each handler's `output` with the table in §1. Several effects = several handlers.
4. Write each state type **once**, then copy it verbatim wherever it's used (`type`, `reads`,
   `derived`).
5. Add `consumes` on every scope that handles an event cascaded by another scope.
6. Give every `data`/`event` handler whose result depends only on the event, its state, its
   `reads` and `params` a `rule` (§5) - using only the names, operators and stages listed there.
   Leave the rest (ids, time, I/O) without one. Give containers that start from a constant or
   a params field an `initial`. Write strings in rules as `'...'`.
   For every call to a server: an endpoint in `remote` (method, path relative to the base URL,
   args, response) and a `request` handler (§6) with `args` for every endpoint arg, `into` as
   `RemoteData<response>`, and `emits` + `onFailure` when the app must react to failures.
7. Add examples only for deterministic handlers (§7), with values exactly as the Kotlin
   constructors expect (parameter names = JSON keys) - ruled handlers included.
8. Self-check (below), then validate: JSON Schema first, then `./gradlew generateTypedScopes`.
   Fix every reported problem and repeat until it passes with no errors.

### Decision table

| Question | Answer |
| --- | --- |
| Handler changes state? | `data`, `type` = that state |
| Handler emits events? | `event`, `event` = the event; needs another handler for it (own scope, or `consumes`) |
| Handler does neither? | `none`, no examples |
| Handler needs other state to decide? | `reads` with those state types (same scope) |
| State computed from other state? | `derived`, no handler writes it |
| Two handlers of one kind for one event? | give the second a `name` |
| Scope must exist with another? | `dependsOn` on the other (target without params) |
| One scope per item/list? | `params` (a `created` type) |
| Event exists in the codebase? | qualified `name`, no `args` |
| Field is a list/map/object? | `generics`/`created` with a `field` name |
| Behaviour is a pure function of event/state/reads/params? | a `rule` (§5) |
| Container starts from a constant or a params field? | an `initial` entry (§5) |
| Needs ids, time, I/O, logging? | no rule - code |
| Loads or sends data over the network? | a `remote` endpoint + a `request` handler (§6) |
| A second trigger while loading is newer / a duplicate / more work? | `policy.concurrency` `latest` / `drop` / `queue` |

### Self-check before validating

- [ ] Every `kind` is one of `primitive`, `generics`, `created`, `data`, `event`, `none`.
- [ ] Every identifier is `[A-Za-z_][A-Za-z0-9_]*`; types are builtin, generated, or qualified.
- [ ] Each state type is spelled identically everywhere.
- [ ] Every `reads`/`derived.from` entry is a state of the same scope.
- [ ] Every cascaded event has a handler in the same scope or in a scope that `consumes` it.
- [ ] Every `consumes` entry is a handler input of that scope.
- [ ] `dependsOn` targets exist and have no `params`.
- [ ] Qualified events have no `args`; simple events define theirs, identically everywhere.
- [ ] Examples: `data` → `given` + `expect`; `event` → `emits`; `none` → none; all `reads` given.
- [ ] Rules: `data` → an expression; `event` → `[when ...] emit <its output event>(...)`; no rule
      on `none`; only `event`, `state`/own container, `reads` names, `params`, `it` (in stages).
- [ ] Requests: `call` is `<Api>.<endpoint>` of `remote`; `args` has exactly the endpoint's args;
      `into` is `RemoteData` of the endpoint's `response`; events in `onSuccess`/`onFailure` are
      in `emits` and handled somewhere; examples have `respond` or `fail`.
- [ ] `version` bumped per §10 if an existing schema changed.

### After generation

Implement each scope from `build/reports/eventThread/tasks/<Scope>.md` - it lists every binding
with its event, state, reads and examples (bindings generated from rules are marked as done). Done when a test extending `<Name>ScopeContract`
passes.
