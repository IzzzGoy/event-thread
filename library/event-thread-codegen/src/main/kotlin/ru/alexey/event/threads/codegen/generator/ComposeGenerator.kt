package ru.alexey.event.threads.codegen.generator

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.UNIT
import ru.alexey.event.threads.codegen.model.ScopeModel

/**
 * Compose entry points per scope (with `generateCompose`), on `event-thread-compose`:
 * - `<Name>ScopeContent(params) { ... }` - mounts the scope while in composition (as `scope()`),
 *   its content running *on* the typed scope: `todos.collectAsState()`, `send(AddTodo(...))`;
 * - `current<Name>Scope()` - the enclosing one, for composables further down.
 *
 * Compose types are referenced by name - this module doesn't depend on Compose.
 */
internal class ComposeGenerator {

    fun generate(context: GeneratorContext, scope: ScopeModel): FileSpec {
        val base = scope.name.removeSuffix("Scope")
        val pkg = context.packages.scopes
        val scopeClass = ClassName(pkg, "${base}Scope")
        val key = ClassName(pkg, context.scopeKeyName(scope))
        val params = scope.params?.let(context.types::typeOf)
        val composable = AnnotationSpec.builder(COMPOSABLE).build()
        val content = LambdaTypeName.get(receiver = scopeClass, returnType = UNIT).copy(annotations = listOf(composable))

        val mount = FunSpec.builder("${base}ScopeContent")
            .addAnnotation(composable)
            .addKdoc(
                "Mounts [%T] while this stays in composition (loaded on enter, freed after the last user leaves -\n" +
                    "as `scope()`), running [content] on it: its containers and `send` functions are in scope.",
                scopeClass,
            )
            .apply { params?.let { addParameter("params", it) } }
            .addParameter(ParameterSpec.builder("scopeHolder", SCOPE_HOLDER.copy(nullable = true)).defaultValue("null").build())
            .addParameter("content", content)
            .addStatement(
                "%M(%T, %L, scopeHolder, { it as? %T }, content)",
                TYPED_SCOPE, key, if (params == null) "Unit" else "params", scopeClass,
            )
            .build()

        val current = FunSpec.builder("current${base}Scope")
            .addAnnotation(composable)
            .addKdoc("The enclosing [%T] - inside [%N] (or a `scope(%T, ...)` of a holder that registered it).", scopeClass, mount.name, key)
            .returns(scopeClass)
            .addStatement(
                "return checkNotNull(%M { it as? %T }) { %S }",
                CURRENT_TYPED_SCOPE, scopeClass, "No ${scopeClass.simpleName} encloses this composable",
            )
            .build()

        return FileSpec.builder(pkg, "${base}ScopeContent")
            .generatedBy(context)
            .addFunction(mount)
            .addFunction(current)
            .build()
    }

    private companion object {
        val COMPOSABLE = ClassName("androidx.compose.runtime", "Composable")
        val SCOPE_HOLDER = CoreSymbols.ScopeHolder
        val TYPED_SCOPE = MemberName("ru.alexey.event.threads", "typedScope")
        val CURRENT_TYPED_SCOPE = MemberName("ru.alexey.event.threads", "currentTypedScope")
    }
}
