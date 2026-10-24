package ru.alexey.event.threads.codegen.generator

import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel

/**
 * Checks of the schema as a whole - what the per-scope model validation can't see. Runtime
 * behaviour is the implementations' business; the *flow* the schema declares can be verified
 * before a line is generated. Every problem is reported at once.
 */
internal object SchemaValidator {

    fun validate(scopes: List<ScopeModel>) {
        val problems = cascadesReachAHandler(scopes)
        require(problems.isEmpty()) {
            "Schema problems:\n" + problems.joinToString("\n") { " - $it" }
        }
    }

    /**
     * A cascaded event lands on its own scope's bus: it reaches a handler there, or - routed by
     * the ScopeHolder - in another scope that `consumes` it. Anything else is an event that goes
     * nowhere.
     */
    private fun cascadesReachAHandler(scopes: List<ScopeModel>): List<String> {
        val handledBy = scopes.flatMap { scope -> scope.handlers.map { it.input.name to scope } }
            .groupBy({ it.first }, { it.second })
        return scopes.flatMap { scope ->
            scope.handlers.mapNotNull { handler ->
                val event = (handler.output as? HandlerOutput.Event)?.event?.name ?: return@mapNotNull null
                val handlers = handledBy[event].orEmpty().distinct()
                val reached = handlers.any { it === scope || event in it.consumes }
                when {
                    reached -> null
                    handlers.isEmpty() ->
                        "$event, cascaded by '${scope.name}' on ${handler.input.name}, has no handler in any scope"
                    else ->
                        "$event, cascaded by '${scope.name}' on ${handler.input.name}, never reaches a handler: " +
                            "${handlers.map { it.name }} handle it but don't consume it"
                }
            }
        }
    }
}
