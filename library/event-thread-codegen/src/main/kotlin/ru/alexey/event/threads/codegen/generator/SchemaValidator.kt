package ru.alexey.event.threads.codegen.generator

import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.ScopeModel

/** What [SchemaValidator] found: [errors] stop generation; [warnings] are reported (and fail it
 * too in strict mode). */
data class SchemaReport(val errors: List<String>, val warnings: List<String>)

/**
 * Checks of the schema as a whole - what the per-scope model validation can't see. Runtime
 * behaviour is the implementations' business; the *flow* the schema declares can be verified
 * before a line is generated. Every problem is reported at once.
 */
object SchemaValidator {

    fun report(scopes: List<ScopeModel>): SchemaReport =
        SchemaReport(errors = cascadesReachAHandler(scopes), warnings = cascadeCycles(scopes) + longRules(scopes))

    /** Throws on errors (and, with [strict], on warnings). */
    fun validate(scopes: List<ScopeModel>, strict: Boolean = false) {
        val report = report(scopes)
        val problems = report.errors + if (strict) report.warnings else emptyList()
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
            scope.handlers.flatMap { handler -> handler.output.cascades.map { handler to it.name } }.mapNotNull { (handler, event) ->
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

    /** Rules are for one-liners; a long one is code without an IDE, a debugger or tests of its
     * own - better written as an implementation. */
    private fun longRules(scopes: List<ScopeModel>): List<String> = scopes.flatMap { scope ->
        scope.handlers.mapNotNull { h ->
            val rule = h.rule ?: return@mapNotNull null
            if (rule.length <= MAX_RULE_LENGTH) null
            else "'${scope.name}' ${h.name ?: h.input.simpleName}: rule is ${rule.length} characters (over $MAX_RULE_LENGTH) - consider writing it as code"
        }
    }

    const val MAX_RULE_LENGTH = 120

    /**
     * Cascade loops: event E in scope S cascades F, which (in S, or routed to a consumer) cascades
     * ... back to E - each dispatch feeding the next. A warning, not an error: a loop guarded by
     * the implementation's own condition (a retry until some state is reached) is legitimate.
     */
    private fun cascadeCycles(scopes: List<ScopeModel>): List<String> {
        data class Node(val scope: String, val event: String) {
            override fun toString() = "${event.substringAfterLast('.')}@$scope"
        }
        val consumers = scopes.flatMap { s -> s.consumes.map { it to s.name } }.groupBy({ it.first }, { it.second })
        val edges: Map<Node, List<Node>> = scopes.flatMap { scope ->
            scope.handlers.flatMap { h -> h.output.cascades.map { h to it.name } }.map { (h, out) ->
                val targets = listOf(scope.name) + consumers[out].orEmpty().filter { it != scope.name }
                Node(scope.name, h.input.name) to targets.map { Node(it, out) }
            }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.flatten().distinct() }

        val cycles = linkedSetOf<List<Node>>()
        fun visit(node: Node, path: List<Node>) {
            val at = path.indexOf(node)
            if (at >= 0) {
                val cycle = path.subList(at, path.size)
                // One report per loop, whichever node it was entered from.
                val start = cycle.indices.minBy { cycle[it].toString() }
                cycles += cycle.drop(start) + cycle.take(start)
                return
            }
            edges[node].orEmpty().forEach { visit(it, path + node) }
        }
        edges.keys.forEach { visit(it, emptyList()) }
        return cycles.map { cycle -> "Cascade loop: " + (cycle + cycle.first()).joinToString(" -> ") }
    }
}
