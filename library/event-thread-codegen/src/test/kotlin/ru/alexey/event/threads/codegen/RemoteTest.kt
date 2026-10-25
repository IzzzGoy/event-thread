package ru.alexey.event.threads.codegen

import ru.alexey.event.threads.codegen.generator.TypedScopeSources
import ru.alexey.event.threads.codegen.model.ScopeSpecFormat
import ru.alexey.event.threads.codegen.generator.SchemaCompatibility
import ru.alexey.event.threads.codegen.generator.ScopeReports
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RemoteTest {

    internal companion object {
        val todo = """{ "kind": "created", "name": "Todo", "field": "todo", "fields": [
            { "kind": "primitive", "value": "id", "typeKey": "Long" },
            { "kind": "primitive", "value": "text", "typeKey": "String" } ] }"""
        val todoRef = """{ "kind": "created", "name": "Todo" }"""
        val todoList = """{ "kind": "generics", "baseClassName": "List", "parameters": [ $todoRef ] }"""
        val listId = """{ "kind": "primitive", "value": "listId", "typeKey": "String" }"""

        fun document(
            request: String = """"call": "Todo.loadTodos", "args": { "listId": "params.listId" },
                "emits": [ { "name": "TodosLoaded", "args": [ { "kind": "primitive", "value": "count", "typeKey": "Int" } ] },
                           { "name": "LoadFailed", "args": [ { "kind": "primitive", "value": "reason", "typeKey": "String" } ] } ],
                "onSuccess": "emit TodosLoaded(result | count)",
                "onFailure": "emit LoadFailed(error.message ?: '')",
                "policy": { "concurrency": "latest", "retry": 2, "timeoutMs": 5000 }""",
            examples: String = "",
            endpoints: String = """
              { "name": "loadTodos", "method": "GET", "path": "lists/{listId}/todos", "args": [ $listId ], "response": $todoList },
              { "name": "addTodo", "method": "POST", "path": "lists/{listId}/todos", "args": [ $listId, $todo ], "body": "todo", "response": $todoRef },
              { "name": "search", "method": "GET", "path": "todos", "args": [ { "kind": "primitive", "value": "q", "typeKey": "String" } ], "response": $todoList },
              { "name": "deleteAll", "method": "DELETE", "path": "lists/{listId}/todos", "args": [ $listId ] }""",
            format: String = "1.2",
        ) = """{ "format": "$format",
          "remote": [ { "name": "Todo", "endpoints": [ $endpoints ] } ],
          "scopes": [ { "name": "Board",
            "params": { "kind": "created", "name": "BoardParams", "fields": [ $listId ] },
            "handlers": [
              { "input": { "name": "Refresh" },
                "output": { "kind": "request",
                  "into": { "kind": "generics", "baseClassName": "RemoteData", "field": "todos", "parameters": [ $todoList ] },
                  $request }$examples },
              { "input": { "name": "TodosLoaded", "args": [ { "kind": "primitive", "value": "count", "typeKey": "Int" } ] } },
              { "input": { "name": "LoadFailed", "args": [ { "kind": "primitive", "value": "reason", "typeKey": "String" } ] } }
            ] } ] }"""

        fun generate(doc: String, ktor: Boolean = true) =
            TypedScopeSources.generate("com.example", ScopeSpecFormat.decodeDocument(doc), ktor = ktor).associate { it.name to it.toString() }
    }

    private fun failure(doc: String): String = assertFailsWith<IllegalArgumentException> { generate(doc) }.message!!

    @Test
    fun apiInterfaceAndKtorImplementation() {
        val api = generate(document()).getValue("TodoRemoteApi")
        assertContains(api, "public interface TodoRemoteApi {")
        assertContains(api, "public suspend fun addTodo(listId: String, todo: Todo): Todo")
        assertContains(api, "public suspend fun deleteAll(listId: String)\n")
        assertContains(api, "public open class TodoRemoteApiKtor(\n  protected val client: HttpClient,\n) : TodoRemoteApi")
        assertContains(api, "url(\"\"\"lists/${'$'}{listId.toString().encodeURLPathPart()}/todos\"\"\")")
        assertContains(api, "import io.ktor.http.encodeURLPathPart")
        assertContains(api, "parameter(\"q\", q)")
        assertContains(api, "contentType(ContentType.Application.Json)\n      setBody(todo)")
        assertContains(api, "response.successOrThrow()\n    return response.body()")
    }

    @Test
    fun noKtorWithoutHttpOrWhenTurnedOff() {
        val off = generate(document(), ktor = false)
        assertFalse("TodoRemoteApiKtor" in off.getValue("TodoRemoteApi"))
        assertContains(off.getValue("BoardScope"), "public fun todoRemoteApi(params: BoardParams): TodoRemoteApi\n")
        assertFalse("httpClient" in off.getValue("BoardScope"))
        // An endpoint without method/path: no Ktor implementation for the API.
        val sdk = generate(document(endpoints = """{ "name": "loadTodos", "args": [ $listId ], "response": $todoList }"""))
        assertFalse("TodoRemoteApiKtor" in sdk.getValue("TodoRemoteApi"))
        assertFalse("httpClient" in sdk.getValue("BoardScope"))
    }

    @Test
    fun requestBaseAndGeneratedImplementation() {
        val handler = generate(document()).getValue("RefreshHandler")
        assertContains(handler, "public abstract class RefreshRequest(\n  deps: Deps,\n) : EventHandler.Request<Refresh, List<Todo>>(deps.todos)")
        assertContains(handler, "final override val outputs: Set<KClass<out Event>> = setOf(TodosLoaded::class, LoadFailed::class)")
        assertContains(handler, "override val policy: RequestPolicy = RequestPolicy(retry = 2, timeoutMs = 5_000)")
        assertContains(handler, "public val todoRemoteApi: TodoRemoteApi,")
        assertContains(handler, "protected override suspend fun call(event: Refresh): List<Todo> = todoRemoteApi.loadTodos(listId = params.listId)")
        assertContains(handler, "protected override fun onSuccess(event: Refresh, result: List<Todo>): List<Event> = buildList {\n    add(TodosLoaded(result.count()))")
        assertContains(handler, "add(LoadFailed(error.message ?: \"\"))")
    }

    @Test
    fun everythingDefaultsAndStaysOverridable() {
        val scope = generate(document()).getValue("BoardScope")
        assertContains(scope, "public fun todos(params: BoardParams): TodosContainer = TodosContainerInitial(RemoteData.Idle)")
        assertContains(scope, "public fun todoRemoteApi(params: BoardParams): TodoRemoteApi = TodoRemoteApiKtor(httpClient(params))")
        assertContains(scope, "public fun refreshRequest(deps: RefreshRequest.Deps, params: BoardParams): RefreshRequest = RefreshRequestRule(deps, params)")
        assertContains(scope, "override fun httpClient(params: BoardParams): HttpClient = resolveOrNull(HttpClient::class, params) ?: super<BoardScopeBindings>.httpClient(params)")
        assertContains(scope, "override fun todoRemoteApi(params: BoardParams): TodoRemoteApi = resolveOrNull(TodoRemoteApi::class, params) ?: super<BoardScopeBindings>.todoRemoteApi(params)")
        assertContains(scope, "val todoRemoteApi = bindings.todoRemoteApi(params)\n    on<Refresh>(bindings.refreshRequest(RefreshRequest.Deps(todos, todoRemoteApi), params))")
    }

    @Test
    fun requestsAreChecked() {
        assertContains(failure(document(request = """"call": "Todos.loadTodos", "args": { "listId": "params.listId" }""")), "no remote API 'Todos' - declared: [Todo]")
        assertContains(failure(document(request = """"call": "Todo.load", "args": { "listId": "params.listId" }""")), "'Todo' has no endpoint 'load'")
        assertContains(failure(document(request = """"call": "Todo.loadTodos", "args": {}""")), "args [listId] of Todo.loadTodos aren't given")
        assertContains(failure(document(request = """"call": "Todo.loadTodos", "args": { "listId": "params.listId", "x": "1" }""")), "Todo.loadTodos has no args [x]")
        assertContains(failure(document(request = """"call": "Todo.loadTodos", "args": { "listId": "evnt" }""")), "arg listId: Unknown name 'evnt'")
        assertContains(failure(document(request = """"call": "Todo.addTodo", "args": { "listId": "''", "todo": "Todo(1, '')" }""")), "into is RemoteData<kotlin.collections.List<com.example.models.Todo>>, but Todo.addTodo responds com.example.models.Todo")
        assertContains(
            failure(document(request = """"call": "Todo.loadTodos", "args": { "listId": "params.listId" },
                "emits": [ { "name": "TodosLoaded", "args": [ { "kind": "primitive", "value": "count", "typeKey": "Int" } ] } ],
                "onSuccess": "emit LoadFailed('x')"""")),
            "This handler's output is TodosLoaded - it can't emit LoadFailed",
        )
        assertContains(
            failure(document(request = """"call": "Todo.loadTodos", "args": { "listId": "params.listId" },
                "emits": [ { "name": "TodosLoaded", "args": [ { "kind": "primitive", "value": "count", "typeKey": "Int" } ] } ],
                "onFailure": "emit TodosLoaded(result | count)"""")),
            "onFailure: Unknown name 'result'",
        )
        val decode = { doc: String -> assertFailsWith<IllegalArgumentException> { ScopeSpecFormat.decodeDocument(doc) }.message!! }
        assertContains(decode(document(request = """"call": "Todo.loadTodos", "onSuccess": "emit X()"""")), "declare them in `emits`")
        assertContains(decode(document(format = "1.1")), "Remote APIs and request handlers need \"format\": \"1.2\"")
        assertContains(decode(document(endpoints = """{ "name": "a", "method": "GET", "path": "x/{id}" }""")), "path placeholders [id] aren't args")
        assertContains(decode(document(endpoints = """{ "name": "a", "method": "GET", "path": "x", "args": [ $listId ], "body": "listId" }""")), "a GET request has no body")
        assertContains(decode(document(endpoints = """{ "name": "a", "method": "GET" }""")), "`method` and `path` go together")
    }

    @Test
    fun emittedEventsMustReachAHandler() {
        val doc = document().replace(""",
              { "input": { "name": "LoadFailed", "args": [ { "kind": "primitive", "value": "reason", "typeKey": "String" } ] } }""", "")
        assertContains(failure(doc), "LoadFailed, cascaded by 'Board' on Refresh, has no handler in any scope")
    }

    @Test
    fun requestExamplesRunAgainstAFakeApi() {
        val examples = """, "examples": [
            { "name": "loads", "given": [ { "id": 1, "text": "old" } ], "respond": [ { "id": 2, "text": "new" } ], "emits": [ { "TodosLoaded": { "count": 1 } } ] },
            { "name": "fails", "fail": "offline", "emits": [ { "LoadFailed": { "reason": "offline" } } ] } ]"""
        val contract = TypedScopeSources.generateTests("com.example", ScopeSpecFormat.decodeDocument(document(examples = examples)))
            .first { it.name == "BoardScopeContract" }.toString()
        assertContains(contract, "val todos = object : TodosContainer() { override val source = flowResource<RemoteData<List<Todo>>>(RemoteData.Success(listOf<Todo>(Todo(id = 1, text = \"old\")))) }")
        assertContains(contract, "override suspend fun loadTodos(listId: String): List<Todo> {\n      return listOf<Todo>(Todo(id = 2, text = \"new\"))")
        assertContains(contract, "override suspend fun search(q: String): List<Todo> {\n      error(\"Todo.search isn't called by this example\")")
        assertContains(contract, "val handler = bindings().refreshRequest(RefreshRequest.Deps(todos, todoRemoteApi), params())")
        assertContains(contract, "val emitted = handler.execute(Refresh)")
        assertContains(contract, "assertEquals(RemoteData.Success(listOf<Todo>(Todo(id = 2, text = \"new\"))), todos.value)")
        assertContains(contract, "assertEquals(listOf<Event>(TodosLoaded(count = 1)), emitted)")
        assertContains(contract, "throw IllegalStateException(\"offline\")")
        assertContains(contract, "assertTrue(state is RemoteData.Failure && state.error.message == \"offline\"")
        assertContains(contract, "assertEquals(listOf<Event>(LoadFailed(reason = \"offline\")), emitted)")
        val decode = { ex: String -> assertFailsWith<IllegalArgumentException> { ScopeSpecFormat.decodeDocument(document(examples = ex)) }.message!! }
        assertContains(decode(""", "examples": [ { "name": "x" } ]"""), "needs `respond` (the response) or `fail`")
    }

    @Test
    fun requestContainersHaveNoInitial() {
        val doc = document().replace(""""params": {""", """"initial": { "todos": "[]" }, "params": {""")
        assertContains(failure(doc), "a request's container starts as RemoteData.Idle")
    }

    @Test
    fun reportsAndVersioning() {
        val doc = ScopeSpecFormat.decodeDocument(document())
        val task = ScopeReports.tasks("com.example", doc).getValue("Board.md")
        assertContains(task, "- [x] `refreshRequest` -> `RefreshRequest`: generated from the request `Todo.loadTodos` (`RefreshRequestRule`) into `todos`")
        assertContains(ScopeReports.diagram(doc), "Board_Refresh_call{{\"Todo.loadTodos\"}}")
        fun levels(a: String, b: String) = SchemaCompatibility.check(
            ScopeSpecFormat.decodeDocument(a), ScopeSpecFormat.decodeDocument(b),
        ).changes.map { it.level to it.description }
        val base = document()
        assertEquals(listOf(SchemaCompatibility.Level.PATCH), levels(base, base.replace("\"retry\": 2", "\"retry\": 3")).map { it.first })
        assertEquals(listOf(SchemaCompatibility.Level.PATCH), levels(base, base.replace("path\": \"todos\"", "path\": \"todos/search\"")).map { it.first })
        val searchOne = base.replace(""""value": "q", "typeKey": "String" } ], "response": $todoList""", """"value": "q", "typeKey": "String" } ], "response": $todoRef""")
        assertEquals(listOf(SchemaCompatibility.Level.MAJOR to "remote Todo.search: signature changed"), levels(base, searchOne))
        assertEquals(listOf(SchemaCompatibility.Level.MINOR), levels(base.replace(",\n              { \"name\": \"deleteAll\"", ",\n              { \"name\": \"deleteAll2\""), base).filter { it.second.endsWith("added") }.map { it.first })
    }
}
