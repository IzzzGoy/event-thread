# Remote data

Loading and sending data over the network, declared in the schema: the remote API, the handler
that calls it, the state it ends in, and the events that follow. Everything is generated: the
API interface, a Ktor implementation, the request handler, its `RemoteData` container. Every
generated piece is an overridable default, like [rules](RULES.md).

Requires `"format": "1.2"`.

```json
{
  "format": "1.2",
  "remote": [
    { "name": "Todo", "endpoints": [
      { "name": "loadTodos", "method": "GET", "path": "lists/{listId}/todos",
        "args": [ { "kind": "primitive", "value": "listId", "typeKey": "String" } ],
        "response": { "kind": "generics", "baseClassName": "List", "parameters": [ { "kind": "created", "name": "Todo" } ] } }
    ] }
  ],
  "scopes": [
    { "name": "Board", "params": { ... listId ... },
      "handlers": [
        { "input": { "name": "Refresh" },
          "output": {
            "kind": "request",
            "call": "Todo.loadTodos",
            "args": { "listId": "params.listId" },
            "into": { "kind": "generics", "baseClassName": "RemoteData", "field": "todos",
                      "parameters": [ { "kind": "generics", "baseClassName": "List", "parameters": [ { "kind": "created", "name": "Todo" } ] } ] },
            "emits": [ { "name": "LoadFailed", "args": [ { "kind": "primitive", "value": "reason", "typeKey": "String" } ] } ],
            "onFailure": "emit LoadFailed(error.message ?: '')",
            "policy": { "concurrency": "latest", "retry": 2, "timeoutMs": 10000 }
          } }
      ] }
  ]
}
```

```kotlin
@Composable
fun Board(listId: String) = BoardScopeContent(BoardParams(listId)) {
    val todos by todos.collectAsState()                      // RemoteData<List<Todo>>
    when (val t = todos) {
        RemoteData.Idle -> LaunchedEffect(Unit) { send(Refresh) }
        is RemoteData.Loading -> Spinner(t.previous)          // the last list, while reloading
        is RemoteData.Success -> TodoList(t.value)
        is RemoteData.Failure -> ErrorView(t.error, t.previous, retry = { send(Refresh) })
    }
}
```

Bind one `HttpClient` in the DependencyProvider; there's nothing else to write.

Contents: [Remote APIs](#remote-apis) · [Request handlers](#request-handlers) ·
[RemoteData](#remotedata) · [Policy](#policy) · [The HttpClient](#the-httpclient) ·
[Overriding](#overriding) · [Examples and tests](#examples-and-tests) · [Checks](#checks) ·
[Versioning](#versioning) · [Limits](#limits)

---

## Remote APIs

The document's `remote` lists APIs; each becomes `<Name>RemoteApi` in the `.remote` package, with
one `suspend fun` per endpoint:

| Endpoint field | |
| --- | --- |
| `name` | the function's name |
| `args` | its parameters (each with a `value`/`field` name) |
| `response` | its return type; absent: `Unit` |
| `method` | `GET`, `POST`, `PUT`, `PATCH`, `DELETE` - with `path`, an HTTP call |
| `path` | relative to the client's base URL; `{arg}` placeholders are path segments (URL-encoded) |
| `body` | the arg sent as the JSON body (`POST`/`PUT`/`PATCH`) |
| `description` | KDoc |

The remaining args (not in the path, not the body) are query parameters.

```kotlin
public interface TodoRemoteApi {
    public suspend fun loadTodos(listId: String): List<Todo>
    public suspend fun addTodo(listId: String, todo: Todo): Todo
}
```

When **every** endpoint of an API has `method` and `path`, `<Name>RemoteApiKtor(client)` is also
generated. It's an `open` implementation over a Ktor `HttpClient`:

```kotlin
override suspend fun addTodo(listId: String, todo: Todo): Todo {
    val response = client.request {
        method = HttpMethod.Post
        url("""lists/${listId.toString().encodeURLPathPart()}/todos""")
        contentType(ContentType.Application.Json)
        setBody(todo)
    }
    response.successOrThrow()          // non-2xx -> HttpStatusException(status, url, body)
    return response.body()
}
```

An API with no HTTP mapping (an SDK, gRPC, Firebase) gets only the interface. Implement it and
bind it.

Created types an endpoint sends or receives, and the types nested in them, are generated
`@Serializable`. The module needs the kotlinx.serialization plugin, which it has anyway for the
client's JSON.

---

## Request handlers

A handler with `"output": { "kind": "request", ... }` becomes an `EventHandler.Request`:

| Field | |
| --- | --- |
| `call` | `"<Api>.<endpoint>"` |
| `args` | every endpoint arg, as a [rule](RULES.md) expression over `event`, `params` and `reads` containers |
| `into` | its container: `RemoteData<R>` of the endpoint's response `R` (`{ "kind": "generics", "baseClassName": "RemoteData", "field": "<name>", "parameters": [ <R> ] }`) |
| `emits` | events it may cascade (enforced, like a cascader's outputs) |
| `onSuccess` | cascading rule run after success; also sees `result` (the response) |
| `onFailure` | cascading rule run after failure (all attempts used); also sees `error` (a `Throwable`) |
| `policy` | see [Policy](#policy) |
| `onMismatch` | as for cascaders: `Strict` (default), `Warning`, `Silent` |

On each trigger:
1. the container becomes `Loading(previous)`;
2. the endpoint is called, with retries and timeout per `policy`;
3. the container becomes `Success(result)`, or `Failure(error, previous)`;
4. `onSuccess`/`onFailure` events are dispatched.

Requests run in the handler's own coroutines, so a slow call never blocks the scope's other
events. Closing the scope cancels a running request.

Generated: a `<Event>Request` base and `<Event>RequestRule`, the implementation the bindings
default to:

```kotlin
public class RefreshRequestRule(deps: RefreshRequest.Deps, private val params: BoardParams) : RefreshRequest(deps) {
    protected override suspend fun call(event: Refresh): List<Todo> = todoRemoteApi.loadTodos(listId = params.listId)
    protected override fun onFailure(event: Refresh, error: Throwable): List<Event> = buildList {
        add(LoadFailed(error.message ?: ""))
    }
}
```

A request's container starts as `RemoteData.Idle`, which is its generated default. Other handlers
may `reads` it like any container.

---

## RemoteData

`ru.alexey.event.threads.scopeholder.typed.RemoteData<T>`:

| State | |
| --- | --- |
| `Idle` | nothing requested yet |
| `Loading(previous)` | a request is running; `previous` is the last loaded value, if any |
| `Success(value)` | |
| `Failure(error, previous)` | the last request failed; `previous` is the last loaded value |

`valueOrNull` is the value if loaded, else the last loaded one. `isLoading` is also available.
Keeping `previous` means a reload or a failed refresh never blanks the screen.

---

## Policy

| Field | Default | |
| --- | --- | --- |
| `concurrency` | `latest` | `latest`: a new trigger cancels the running request (refresh, search). `drop`: triggers while one runs are ignored ("load more", double taps). `queue`: triggers run one after another (saves). |
| `retry` | `0` | extra attempts after a failure |
| `retryDelayMs` | `1000` | before the first retry; doubles each time |
| `timeoutMs` | none | per attempt; a timeout is a failure (`RequestTimeoutException`), and is retried |
| `debounceMs` | `0` | waits before calling; with `latest`, a newer trigger replaces a pending one (search-as-you-type) |

With `latest`, "newer" means by the order the scope *received* the triggers. Events are
dispatched concurrently, but an older trigger arriving late never replaces a newer one. With
`queue`, triggers sent at the very same instant may still run in either order, as with any
handler.

---

## The HttpClient

The generated Ktor APIs use one `HttpClient` per scope, from the bindings' `httpClient()`. By
default the DI adapter resolves it from the DependencyProvider:

```kotlin
single<HttpClient> {
    HttpClient(OkHttp) {                                     // Darwin on iOS, MockEngine in tests
        install(ContentNegotiation) { json() }
        defaultRequest { url("https://api.example.com/v1/") } // paths are relative to it
        // auth, logging, ... - the client's business
    }
}
```

Paths have no leading `/`, so they resolve under the base URL's path (`v1/lists/...`). Ktor itself
doesn't throw on non-2xx responses; the generated code does, via `successOrThrow()`.

The plugin adds `event-thread-network` (which brings `ktor-client-core`) when the schema has an
HTTP API. Turn generation of Ktor implementations off with `generateKtor = false`: the API
bindings then become abstract.

---

## Overriding

Every piece is a bindings member with a generated default. Bind your own in the
DependencyProvider, or override it in a hand-written bindings class:

| Member | Default | Replace it to |
| --- | --- | --- |
| `httpClient()` | none (resolved from DI) | supply the client per scope |
| `todoRemoteApi()` | `TodoRemoteApiKtor(httpClient())` | use another transport, a fake, caching |
| `refreshRequest(deps)` | `RefreshRequestRule(deps)` | customise the call, e.g. combine two endpoints |
| `todos()` | `TodosContainerInitial(RemoteData.Idle)` | seed it from a cache |

A hand-written request extends the generated base. Its policy, outputs and container are already
set:

```kotlin
class CachedRefresh(deps: Deps, private val cache: TodoCache) : RefreshRequest(deps) {
    override suspend fun call(event: Refresh) = cache.getOrPut { todoRemoteApi.loadTodos("work") }
}
```

---

## Examples and tests

A request's examples run in its contract (see [TESTING.md](TESTING.md)) against a **fake API**.
The endpoint called answers `respond`, or throws `fail`; other endpoints fail the test:

```json
"examples": [
  { "name": "loads", "given": [ { "id": 1, "text": "old" } ], "respond": [ { "id": 2, "text": "new" } ], "emits": [] },
  { "name": "offline", "fail": "offline", "emits": [ { "reason": "offline" } ] }
]
```

| Field | |
| --- | --- |
| `given` | the last loaded value - the container starts `Success(given)` (absent: `Idle`) |
| `respond` | the endpoint's response - the container must end `Success(respond)` |
| `fail` | the endpoint throws with this message - the container must end `Failure` with it, keeping `given` |
| `reads` | values of `reads` containers, as for other handlers |
| `event` | the trigger's args |
| `emits` | the events cascaded, in order. With several `emits` types, tag each: `{ "LoadFailed": { "reason": "offline" } }` |

The test runs one `execute(event)` of the handler your bindings build, with no bus, policy or
network. For end-to-end tests, use the generated probe with a mock HTTP engine. The sample's
`TypedSuggestionsTest` drives the real generated Ktor API against a `MockEngine`.

---

## Checks

At generation:

| Check | Message |
| --- | --- |
| `call` names a declared API and endpoint | `no remote API 'X' - declared: [...]` / `'X' has no endpoint 'y'` |
| `args` covers exactly the endpoint's args | `args [...] of X.y aren't given` / `X.y has no args [...]` |
| `into` is `RemoteData` of the response type | `into is RemoteData<A>, but X.y responds B` |
| Arg, `onSuccess` and `onFailure` rules are valid | `arg listId: Unknown name ...` / `onFailure: Unknown name 'result'` |
| `onSuccess`/`onFailure` emit only `emits` events | `This handler emits only [...] - it can't emit Y` |
| `onSuccess`/`onFailure` need `emits` | `declare them in emits` |
| Every emitted event reaches a handler | `X, cascaded by 'S' on E, has no handler in any scope` |
| Path placeholders are args; `body` is an arg, not with GET/DELETE | `path placeholders [...] aren't args` / `a GET request has no body` |
| `method` and `path` together | `` `method` and `path` go together `` |
| No `initial` for a request's container | `a request's container starts as RemoteData.Idle` |
| Examples: `respond` or `fail` | `a request's example needs respond ... or fail` |
| `"format": "1.2"` | `Remote APIs and request handlers need "format": "1.2"` |

---

## Versioning

| Change | Bump |
| --- | --- |
| API or endpoint added | MINOR |
| API or endpoint removed; an endpoint's args or response changed | MAJOR - implementations break |
| An endpoint's `method`/`path`/`body` changed | PATCH - only the generated Ktor mapping changes |
| A request's `call`, `into` or `emits` changed | MAJOR |
| A request's `args`, `onSuccess`, `onFailure`, `policy` or `onMismatch` changed | PATCH |

---

## Limits

- One endpoint per request. To combine calls, override the request's `call`.
- JSON bodies only, in the generated Ktor code. For other encodings, override the API.
- No streams (WebSockets, server-sent events) in the schema yet. `webSocketResource`
  (event-thread-network) backs a container's `source` by hand.
- Pagination is events plus requests: a `LoadMore` request with `drop`, and a data rule that
  appends pages. Neither is built in.
