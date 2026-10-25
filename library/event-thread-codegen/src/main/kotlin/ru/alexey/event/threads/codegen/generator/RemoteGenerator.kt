package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
import ru.alexey.event.threads.codegen.model.EndpointModel
import ru.alexey.event.threads.codegen.model.RemoteApiModel

/**
 * The `.remote` package: per remote API, `<Name>RemoteApi` - a `suspend fun` per endpoint - and,
 * when every endpoint is an HTTP call ([ktor]), `<Name>RemoteApiKtor`: the implementation over a
 * Ktor `HttpClient` that the scopes' bindings default to. Paths are relative to the client's base
 * URL (`defaultRequest { url("https://api.example.com/v1/") }`); bodies are JSON (install
 * `ContentNegotiation`); a non-2xx response throws `HttpStatusException` (event-thread-network).
 *
 * Ktor types are referenced by name - this module doesn't depend on Ktor.
 */
internal class RemoteGenerator(private val ktor: Boolean) {

    fun generate(context: GeneratorContext): List<FileSpec> = context.remote.map { api ->
        val file = FileSpec.builder(context.apiClass(api)).generatedBy(context).addType(api(context, api))
        if (ktor && api.isHttp) {
            file.addType(ktorImpl(context, api))
            // Called inside the path template, which KotlinPoet doesn't scan for imports.
            if (api.endpoints.any { it.pathArgs.isNotEmpty() }) file.addImport(ENCODE.packageName, ENCODE.simpleName)
        }
        file.build()
    }

    private fun signature(context: GeneratorContext, endpoint: EndpointModel) = FunSpec.builder(endpoint.name)
        .addModifiers(KModifier.SUSPEND)
        .apply { endpoint.description?.let { addKdoc("%L", it) } }
        .apply { endpoint.args.forEach { addParameter(it.fieldName!!, context.types.typeOf(it)) } }
        .returns(context.responseType(endpoint))

    private fun api(context: GeneratorContext, api: RemoteApiModel): TypeSpec =
        TypeSpec.interfaceBuilder(context.apiClass(api))
            .addKdoc(
                "%L\n\nCalled by the schema's `request` handlers; bound per scope through its bindings%L.",
                api.description ?: "The ${api.name} remote API.",
                if (ktor && api.isHttp) " - [${context.ktorClass(api).simpleName}] by default" else "",
            )
            .addFunctions(api.endpoints.map { signature(context, it).addModifiers(KModifier.ABSTRACT).build() })
            .build()

    private fun ktorImpl(context: GeneratorContext, api: RemoteApiModel): TypeSpec =
        TypeSpec.classBuilder(context.ktorClass(api))
            .addModifiers(KModifier.OPEN)
            .addKdoc("[%T] over [client] - its base URL, JSON setup and auth are the client's.", context.apiClass(api))
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("client", HTTP_CLIENT).build())
            .addProperty(PropertySpec.builder("client", HTTP_CLIENT, KModifier.PROTECTED).initializer("client").build())
            .addSuperinterface(context.apiClass(api))
            .addFunctions(api.endpoints.map { endpoint ->
                val body = CodeBlock.builder()
                    .beginControlFlow("val response = client.%M", REQUEST)
                    .addStatement("method = %T.%L", HTTP_METHOD, KTOR_METHODS.getValue(endpoint.method!!.name))
                    .addStatement("%M(%P)", URL, path(endpoint))
                    .apply {
                        endpoint.queryArgs.forEach { addStatement("%M(%S, %N)", PARAMETER, it, it) }
                        endpoint.body?.let {
                            addStatement("%M(%T.Application.Json)", CONTENT_TYPE, CONTENT_TYPE_CLASS)
                            addStatement("%M(%N)", SET_BODY, it)
                        }
                    }
                    .endControlFlow()
                    .addStatement("response.%M()", SUCCESS_OR_THROW)
                    .apply { if (context.responseType(endpoint) != UNIT) addStatement("return response.%M()", BODY) }
                    .build()
                signature(context, endpoint).addModifiers(KModifier.OVERRIDE).addCode(body).build()
            })
            .build()

    /** `lists/${listId.toString().encodeURLPathPart()}/todos` - placeholders encoded, relative. */
    private fun path(endpoint: EndpointModel): String =
        endpoint.path!!.removePrefix("/").replace("$", "\${'$'}").let { path ->
            EndpointModel.PLACEHOLDER.replace(path) { "\${${it.groupValues[1]}.toString().encodeURLPathPart()}" }
        }

    private companion object {
        val HTTP_CLIENT = ClassName("io.ktor.client", "HttpClient")
        val HTTP_METHOD = ClassName("io.ktor.http", "HttpMethod")
        val CONTENT_TYPE_CLASS = ClassName("io.ktor.http", "ContentType")
        val REQUEST = MemberName("io.ktor.client.request", "request")
        val URL = MemberName("io.ktor.client.request", "url")
        val PARAMETER = MemberName("io.ktor.client.request", "parameter")
        val SET_BODY = MemberName("io.ktor.client.request", "setBody")
        val CONTENT_TYPE = MemberName("io.ktor.http", "contentType")
        val BODY = MemberName("io.ktor.client.call", "body")
        val SUCCESS_OR_THROW = MemberName("ru.alexey.event.threads", "successOrThrow")
        val KTOR_METHODS = mapOf("GET" to "Get", "POST" to "Post", "PUT" to "Put", "PATCH" to "Patch", "DELETE" to "Delete")
        /** Referenced so the generated path's `encodeURLPathPart()` is imported. */
        val ENCODE = MemberName("io.ktor.http", "encodeURLPathPart")
    }
}
