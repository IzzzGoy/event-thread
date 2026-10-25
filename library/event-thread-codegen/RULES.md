# Rules

The rule language: behaviour written in the schema instead of in Kotlin, for the handlers (and
initial states) that are a plain function of their inputs. The generator compiles every rule to
ordinary Kotlin, checks it at generation, and makes it the **default** of its binding - an
implementation of your own still replaces it.

```json
{ "input": { "name": "DeleteTodo", "args": [ <id> ] },
  "output": { "kind": "data", "type": <todos> },
  "rule": "state | remove where it.id == event.id" }
```

```kotlin
// generated
public class DeleteTodoStateProcessorRule(deps: DeleteTodoStateProcessor.Deps) : DeleteTodoStateProcessor(deps) {
  protected override suspend fun process(event: DeleteTodo, state: List<Todo>): List<Todo> =
      state.filterNot { it.id == event.id }
}
```

Contents: [Where rules go](#where-rules-go) · [Names](#names) · [Values](#values) ·
[Operators](#operators) · [Pipelines](#pipelines) · [Cascading rules](#cascading-rules) ·
[Initial values](#initial-values) · [Checks](#checks) · [Defaults and overrides](#defaults-and-overrides) ·
[Testing](#testing) · [Versioning](#versioning) · [Cookbook](#cookbook) · [Limits](#limits) ·
[Grammar](#grammar)

Rules need `"format": "1.1"` in the schema document (requests: `"1.2"`, see [REMOTE.md](REMOTE.md)).

---

## Where rules go

| Field | On | Is | Becomes |
| --- | --- | --- | --- |
| `rule` | a `data` handler | an expression: the **new state** | `<Handler>Rule.process(event, state)` |
| `rule` | an `event` handler | `emit` clauses: the **events to cascade** | `<Handler>Rule.cascade(input)` |
| `initial` | a scope: `{ "<container>": "<expr>" }` | an expression: the container's **initial state** | `<State>ContainerInitial(<expr>)` |
| `args` | a `request` output: `{ "<endpoint arg>": "<expr>" }` | an expression per argument of the remote call | the generated request's `call` |
| `onSuccess` / `onFailure` | a `request` output | `emit` clauses, also seeing `result` / `error` | its `onSuccess` / `onFailure` |

A side effect (`none`) can't have a rule - there's nothing to compute. A derived container can't
have an `initial` - its state comes from its `transforms`.

**Use a rule when** the result depends only on the event, the handler's own state, the
containers it `reads`, and the scope's `params`. **Write code** for anything else: generated ids,
the clock, I/O, logging, calling into your own classes.

---

## Names

| Name | Means | Available in |
| --- | --- | --- |
| `event` | the input event | handler rules |
| `state` | the handler's own state, before the event | `data` rules |
| the handler's container name (`todos`) | same as `state` | `data` rules |
| a `reads` container's name (`totalAdded`) | that container's current value (a snapshot read) | handlers with `reads` |
| `params` | the scope instance's params | scopes with `params` |
| `it` | the current element | inside a pipeline stage |
| `result` | the remote call's response | a request's `onSuccess` |
| `error` | the failure (a `Throwable`: `error.message ?: ''`) | a request's `onFailure` |
| a type's simple name (`Todo`, `AddLimitReached`) | `Todo(...)` constructs it; a bare `Done` is an `object` | every type and event the schema mentions |

Fields are reached with `.`: `event.text`, `params.listId`, `it.done`, `event.todo.id` - and with
`?.` on a nullable value: `(state | first it.done)?.text`.

Types the rule can name are every event (input or output) and every type the schema mentions -
`created` types and qualified type references (`org.example.Todo` is `Todo` in a rule). A simple
name two schema types share is ambiguous and reported if used.

---

## Values

| Write | Kotlin |
| --- | --- |
| `1`, `-3`, `2.5` | number literals |
| `'text'` or `"text"` | string - prefer `'...'`: rules live in JSON strings, where `"` must be escaped (`\"`) |
| `true`, `false`, `null` | literals |
| `[a, b]` | `listOf(a, b)` |
| `[]` | `emptyList()` |
| `Todo(id: event.id, text: event.text)` | `Todo(id = event.id, text = event.text)` |
| `AddLimitReached(totalAdded)` | positional: `AddLimitReached(totalAdded.value)` |
| `x with (done: true, text: 'a')` | `x.copy(done = true, text = "a")` |
| `if c then a else b` | `if (c) a else b` |
| `( ... )` | grouping |

---

## Operators

From loosest to tightest:

| Operator | Kotlin | Note |
| --- | --- | --- |
| `if ... then ... else ...` | `if (...) ... else ...` | |
| `a ?: b` | `a ?: b` | `b` when `a` is null |
| `list \| stage ...` | a call chain | see [Pipelines](#pipelines) |
| `or`, `\|\|` | `\|\|` | |
| `and`, `&&` | `&&` | |
| `==`, `!=` | `==`, `!=` | structural equality |
| `<`, `<=`, `>`, `>=` | same | |
| `+`, `-` | same | on lists: `+` appends an element or a list, `-` removes one |
| `*`, `/`, `%` | same | |
| `not`, `!`, unary `-` | `!`, `-` | |
| `.field`, `?.field`, `with (...)` | `.field`, `?.field`, `.copy(...)` | `?.`: `null` when the left side is |

The generator parenthesizes the Kotlin it emits wherever Kotlin's own precedence differs (Kotlin's
`?:` binds tighter than comparisons), so a rule always means what it says.

---

## Pipelines

`source | stage | stage ...` - each stage takes the list on its left. A stage's expression runs
once per element, with the element as `it`.

| Stage | Result | Kotlin |
| --- | --- | --- |
| `where <cond>` | elements matching | `filter { }` |
| `remove where <cond>` | elements not matching | `filterNot { }` |
| `select <expr>` | each element mapped | `map { }` |
| `update where <cond> set (f: v, ...)` | matching elements copied with new fields, the rest unchanged | `map { if (cond) it.copy(...) else it }` |
| `count` / `count <cond>` | how many (matching) | `count()` / `count { }` |
| `any <cond>` / `all <cond>` / `none <cond>` | Boolean | `any { }` / `all { }` / `none { }` |
| `sum` / `sum <expr>` | the sum (of the expression) | `sum()` / `sumOf { }` |
| `first` / `first <cond>` | that element, or `null` | `firstOrNull()` / `firstOrNull { }` |
| `last` / `last <cond>` | that element, or `null` | `lastOrNull()` / `lastOrNull { }` |
| `sortBy <expr>` / `sortByDescending <expr>` | sorted by the key | `sortedBy { }` / `sortedByDescending { }` |
| `sorted` | sorted naturally | `sorted()` |
| `distinct` | without duplicates | `distinct()` |
| `take <n>` / `drop <n>` | the first `n` / all but the first `n` | `take(n)` / `drop(n)` |

```text
state | where not it.done | select it.text | count
state | update where it.id == event.id set (done: not it.done)
(state | first it.id == event.id)?.text ?: ''
```

- A pipeline binds **loosest**: to use its result in an expression, parenthesize it -
  `(state | count) > 3`, `if (state | any it.done) then ... else ...`.
- `first`/`last` can be empty - follow them with `?:` or `?.`.
- Stage words (`count`, `first`, `select`, ...) are keywords only right after `|`; a container
  called `count` is still usable by name.
- Pipelines nest, and the inner `it` is the inner element (as in Kotlin):
  `state | where (other | any it.id == 1)`.

---

## Cascading rules

An `event` handler's rule says which events to emit - one or more clauses separated by `;`:

```text
[when <condition>] emit <event>[, <event> ...]
```

```text
when totalAdded >= 5 emit AddLimitReached(totalAdded)
emit Saved(event.id); when (state | count) > 100 emit ListFull(100)
when event.text == '' emit Rejected('empty')
emit Logged(event.id), Logged(0)
```

Each clause emits its events when its condition holds (always, without `when`), in order. Every
emitted event is the handler's declared output type - a cascader has one. The generated `cascade`
builds the list:

```kotlin
protected override fun cascade(input: AddTodo): List<Event> = buildList {
    if (totalAdded.value >= 5) add(AddLimitReached(totalAdded.value))
}
```

A cascading handler has no `state` of its own - put the containers it decides on in `reads`.

---

## Initial values

A scope's `initial` gives containers their starting state - one expression per container, by
container name:

```json
{
  "name": "Work",
  "params": { "kind": "created", "name": "ListParams", "fields": [ ... ] },
  "initial": {
    "showCompleted": "true",
    "limitHits": "0",
    "title": "params.name",
    "todos": "[]"
  },
  "handlers": [ ... ]
}
```

- Names: `params` (if the scope has params) and type names - no `event`, `state` or `it`.
- Generates `<State>ContainerInitial(initial)` - an `open` subclass of the container with `source =
  flowResource(initial)` - and makes `ShowCompletedContainerInitial(true)` the container binding's
  default.
- Different scopes sharing a container class can give it different initials.
- Not for derived containers; not for state that comes from storage, the network or a specific
  dispatcher - bind a container implementation for those (e.g. a cache-backed `TodosContainer`, or
  a container on `Dispatchers.Main` with `onEach` logging).

---

## Checks

At generation - every problem at once, with the column of the rule it's about:

```text
Rule problems:
 - DeleteTodoStateProcessor rule: Unknown name 'evnt' - this rule can use: event, state, todos
  state | where it.id == evnt.id
                         ^
```

| Check | Message |
| --- | --- |
| Syntax | `Unexpected ...`, `Expected ...`, `Unterminated string`, `Unexpected character` |
| Unknown name | `Unknown name 'x' - this rule can use: ...` |
| `it` outside a stage | `` `it` is only defined inside a pipeline stage `` |
| `state` in a cascading rule | `` `state` is a state handler's own state ... add the container to `reads` `` |
| `params` without params | `This scope has no params` |
| `event` in an initial value | `There's no event here - an initial value can use params and literals` |
| Unknown or ambiguous type | `Unknown type 'X' - the schema's types: ...` / `'X' names more than one type` |
| `emit` in a `data` rule / initial value | `A state handler's rule is an expression ...` / `An initial value is an expression` |
| Expression in an `event` rule | `A cascading handler's rule emits events: [when condition] emit X(...)` |
| Emitting an event other than the declared output | `This handler's output is X - it can't emit Y` |
| `with`/`set` without field names | `` `with` takes named fields `` |
| A rule on a side effect | `a side effect can't have a rule` |
| `initial` for a derived container / unknown container | `a derived container's state comes from its transforms` / `no such container` |
| A shared handler's rule uses `params`, scopes' params types differ | `... have different params types` |
| No `"format": "1.1"` | `Handler rules and initial values need "format": "1.1"` |
| A rule over 120 characters | **warning** (error with `strictSchema`): `consider writing it as code` |

**Types are checked by the Kotlin compiler**, when the generated class compiles: `state + 1` on a
list, `it.titel`, a constructor argument of the wrong type. The generated class's KDoc quotes the
rule, so the compile error points straight back to it.

---

## Defaults and overrides

A rule (or `initial`) is the **default** of its member in `<Name>ScopeBindings`, not a lock:

```kotlin
public interface WorkScopeBindings {
    public fun showCompleted(params: ListParams): ShowCompletedContainer = ShowCompletedContainerInitial(true)
    public fun deleteTodoStateProcessor(deps: DeleteTodoStateProcessor.Deps, params: ListParams): DeleteTodoStateProcessor =
        DeleteTodoStateProcessorRule(deps)
    public fun addTodoStateProcessor(deps: AddTodoStateProcessor.Deps, params: ListParams): AddTodoStateProcessor   // no rule: yours
}
```

| You use | The default applies | To replace it |
| --- | --- | --- |
| `<Name>ScopeDependencyBindings` (`register<Name>Scope()`) | when the DependencyProvider has no binding for the type | bind one: `factory<DeleteTodoStateProcessor> { (deps: ...) -> MyDelete(deps) }` |
| your own `<Name>ScopeBindings` | when you don't override the member | override it |

So a handler can start as a rule and become code the day it needs more - without touching the
schema, the scope or the rest of the bindings.

---

## Testing

- Schema `examples` test whichever implementation the bindings produce - the rule, unless you
  replaced it. Give ruled handlers examples: they're the rule's tests.
- The generated contract's `everyBindingIsProvided` builds the defaults like any other binding.
- What the compiler doesn't check (what a rule *computes*) only examples check - see
  [TESTING.md](TESTING.md).

---

## Versioning

For `checkScopesCompatibility`:

| Change | Bump |
| --- | --- |
| a `rule` / `initial` added | MINOR - a binding gains a default |
| a `rule` / `initial` changed | PATCH - same contract, other default behaviour |
| a `rule` / `initial` removed | MAJOR - bindings that relied on the default no longer compile |

---

## Cookbook

| Behaviour | Rule |
| --- | --- |
| Set from the event | `event.show` |
| Count | `state + 1` |
| Append | `state + Todo(id: event.id, text: event.text, done: false)` |
| Append unless present | `if (state \| any it.id == event.id) then state else state + event.todo` |
| Remove by id | `state \| remove where it.id == event.id` |
| Toggle a flag | `state \| update where it.id == event.id set (done: not it.done)` |
| Rename | `state \| update where it.id == event.id set (text: event.text)` |
| Clear completed | `state \| remove where it.done` |
| Replace all | `event.items` |
| Reset | `[]` |
| Update a field of an object state | `state with (title: event.title)` |
| Clamp | `if state + 1 > params.max then params.max else state + 1` |
| Keep the newest 20 | `(state + event.entry) \| sortByDescending it.at \| take 20` |
| Emit over a threshold | `when totalAdded >= 5 emit AddLimitReached(totalAdded)` |
| Emit always | `emit Saved(event.id)` |
| Emit with a computed field | `emit Rejected(reason: if event.text == '' then 'empty' else 'too long')` |
| Initial: constant / from params | `"0"`, `"'Work'"`, `"params.initialText"` |

---

## Limits

- Expressions only: no variables, loops, lambdas of your own, or function calls beyond the stages
  - by design; a rule that needs them is code.
- No I/O, randomness, time, or access to anything outside the event, state, reads and params.
- No `Map`/`Set` stages - pipelines work on lists (`List`/`Iterable` state).
- Literals follow Kotlin's typing (`1` is an `Int`, `1.0` a `Double`); a mismatch with a field's
  type is a Kotlin compile error on the generated class - compare fields with fields where you can.
- A cascading rule emits only its one declared output type, like the handler itself.

---

## Grammar

```text
rule    := clause (';' clause)* | expr          -- clauses: cascading handlers; expr: state handlers, initial values
clause  := ['when' expr] 'emit' expr (',' expr)*
expr    := 'if' expr 'then' expr 'else' expr | elvis
elvis   := pipe ('?:' pipe)*
pipe    := or ('|' stage)*
stage   := 'where' or | 'remove' 'where' or | 'select' or | 'update' 'where' or 'set' '(' args ')'
         | 'count' [or] | 'any' or | 'all' or | 'none' or | 'sum' [or] | 'first' [or] | 'last' [or]
         | 'sortBy' or | 'sortByDescending' or | 'sorted' | 'distinct' | 'take' or | 'drop' or
or      := and (('or' | '||') and)*
and     := eq (('and' | '&&') eq)*
eq      := cmp (('==' | '!=') cmp)*
cmp     := add (('<' | '<=' | '>' | '>=') add)*
add     := mul (('+' | '-') mul)*
mul     := unary (('*' | '/' | '%') unary)*
unary   := ('not' | '!' | '-') unary | postfix
postfix := primary (('.' | '?.') NAME | 'with' '(' args ')')*
primary := NUMBER | STRING | 'true' | 'false' | 'null' | '[' [expr (',' expr)*] ']'
         | TYPE '(' [args] ')' | NAME | '(' expr ')'
args    := arg (',' arg)*        arg := [NAME ':'] expr      -- positional: constructions only
```

`STRING` is `'...'` or `"..."` with `\` escapes; a `TYPE` is a name starting upper-case followed by
`(`. Keywords: `if then else when emit with and or not true false null` (stage words only after
`|`).
