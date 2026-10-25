package ru.alexey.event.threads.codegen.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A remote API the schema's `request` handlers call - generated as `<Name>RemoteApi`, a
 * `suspend` function per [endpoints] entry. When every endpoint declares `method` and `path`,
 * also `<Name>RemoteApiKtor` - an implementation over a Ktor `HttpClient`, the bindings' default.
 */
@Serializable
data class RemoteApiModel(
    val name: String,
    val description: String? = null,
    val endpoints: List<EndpointModel>,
) {
    init {
        requireIdentifier(name, "Remote API name")
        require(endpoints.isNotEmpty()) { "Remote API '$name' has no endpoints" }
        val duplicates = endpoints.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Remote API '$name' declares endpoints $duplicates more than once" }
    }

    /** Whether a Ktor implementation can be generated - every endpoint is an HTTP call. */
    val isHttp: Boolean get() = endpoints.all { it.method != null }
}

/**
 * One call: `suspend fun <name>(<args>): <response>`. With [method] and [path] it's an HTTP call:
 * `{arg}` placeholders in [path] are path segments, [body] names the arg sent as the JSON body,
 * every other arg is a query parameter.
 */
@Serializable
data class EndpointModel(
    val name: String,
    val method: HttpMethod? = null,
    /** Relative to the client's base URL (`defaultRequest { url(...) }`), e.g. `lists/{listId}/todos`. */
    val path: String? = null,
    val args: List<ArgDef> = emptyList(),
    /** The arg sent as the request body (POST/PUT/PATCH). */
    val body: String? = null,
    /** The response body's type; absent: no body (`Unit`). */
    val response: ArgDef? = null,
    val description: String? = null,
) {
    init {
        requireIdentifier(name, "Endpoint name")
        require((method == null) == (path == null)) { "Endpoint '$name': `method` and `path` go together - an HTTP call needs both" }
        val names = args.mapIndexed { i, arg ->
            requireNotNull(arg.fieldName) { "Endpoint '$name' arg #$i has no name - set its `value`/`field`" }
        }
        require(names.distinct().size == names.size) { "Endpoint '$name' has duplicate args" }
        val placeholders = path?.let { PLACEHOLDER.findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
        val unknown = placeholders - names.toSet()
        require(unknown.isEmpty()) { "Endpoint '$name': path placeholders $unknown aren't args" }
        body?.let {
            require(it in names) { "Endpoint '$name': body '$it' isn't an arg" }
            require(it !in placeholders) { "Endpoint '$name': body '$it' is also a path placeholder" }
            require(method == null || method.hasBody) { "Endpoint '$name': a $method request has no body" }
        }
    }

    val pathArgs: List<String> get() = path?.let { PLACEHOLDER.findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()

    /** Args that are neither path segments nor the body. */
    val queryArgs: List<String> get() = args.mapNotNull { it.fieldName } - pathArgs.toSet() - setOfNotNull(body)

    companion object {
        val PLACEHOLDER = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")
    }
}

@Serializable
enum class HttpMethod(val hasBody: Boolean) {
    GET(false), POST(true), PUT(true), PATCH(true), DELETE(false)
}

/** `RequestPolicy` of core, as written in the schema. */
@Serializable
data class RequestPolicyModel(
    val concurrency: Concurrency = Concurrency.latest,
    val retry: Int = 0,
    val retryDelayMs: Long = 1000,
    val timeoutMs: Long? = null,
    val debounceMs: Long = 0,
) {
    init {
        require(retry >= 0) { "policy.retry must be >= 0" }
        require(retryDelayMs >= 0 && debounceMs >= 0) { "policy delays must be >= 0" }
        require(timeoutMs == null || timeoutMs > 0) { "policy.timeoutMs must be > 0" }
    }

    @Serializable
    enum class Concurrency {
        @SerialName("latest") latest,
        @SerialName("drop") drop,
        @SerialName("queue") queue,
    }

    val isDefault: Boolean get() = this == RequestPolicyModel()
}
