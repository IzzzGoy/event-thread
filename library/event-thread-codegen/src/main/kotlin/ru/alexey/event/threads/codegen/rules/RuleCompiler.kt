package ru.alexey.event.threads.codegen.rules

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.joinToCode

/**
 * What a rule can see, from the handler it belongs to.
 * - [eventParameter]: the generated function's event parameter (`event` in `process`, `input` in
 *   `cascade`) - the rule always calls it `event`;
 * - [stateNames]: names for the handler's own state (`state`, and its container's name) - empty
 *   for a cascading handler;
 * - [reads]: snapshot names (container names of `reads`) -> the property holding the snapshot;
 * - [params]: whether the scope has params;
 * - [types]: simple name -> class, for constructions (`Todo(...)`) and objects (`Done`); `null`
 *   value: a name two schema types share;
 * - [output]: the declared output event of a cascading handler (simple name).
 */
class RuleEnvironment(
    /** `null`: no event - a container's initial value. */
    val eventParameter: String?,
    val stateNames: Set<String>,
    val reads: Map<String, String>,
    val params: Boolean,
    val types: Map<String, ClassName?>,
    val output: String?,
)

/** [body]: the function body (a `return ...` statement); [usesParams]: it reads `params`. */
class CompiledRule(val body: CodeBlock, val usesParams: Boolean)

/**
 * Compiles a parsed [Rule] to Kotlin. Checked here: shape (an expression for a state handler,
 * `emit` clauses for a cascading one), every name, every constructed type, and that a cascading
 * rule emits only its declared output. Types of values are left to the Kotlin compiler - the
 * generated code is plain Kotlin over the schema's own types, so a mismatch is a compile error on
 * the generated `...Rule` class, whose KDoc carries the rule.
 *
 * Emitted code uses non-breaking spaces (`·`): KotlinPoet may wrap a line at a plain space, and a
 * line break before a binary operator would change the meaning.
 */
class RuleCompiler(private val env: RuleEnvironment) {

    private var usesParams = false
    private lateinit var source: String

    /** A bare expression - a container's `initial` value: no `return`, no `emit`. */
    fun compileExpression(rule: Rule): CompiledRule {
        source = rule.source
        if (rule.expr == null) fail(0, "An initial value is an expression - `emit` is for cascading handlers")
        return CompiledRule(emit(rule.expr, depth = 0, min = 0), usesParams)
    }

    fun compile(rule: Rule): CompiledRule {
        source = rule.source
        val body = if (env.output == null) {
            if (rule.expr == null) fail(0, "A state handler's rule is an expression for the new state - `emit` is for cascading handlers")
            CodeBlock.of("return·%L\n", emit(rule.expr, depth = 0, min = 0))
        } else {
            if (rule.expr != null) fail(0, "A cascading handler's rule emits events: `[when condition] emit ${env.output}(...)`")
            cascade(rule.clauses)
        }
        return CompiledRule(body, usesParams)
    }

    private fun cascade(clauses: List<Clause>): CodeBlock {
        val code = CodeBlock.builder().beginControlFlow("return·buildList")
        clauses.forEach { clause ->
            val events = clause.events.map { event ->
                checkOutput(event)
                emit(event, depth = 0, min = 0)
            }
            val add = if (events.size == 1) CodeBlock.of("add(%L)", events.single()) else CodeBlock.of("addAll(listOf(%L))", events.joinToCode(",·"))
            if (clause.condition == null) code.addStatement("%L", add)
            else code.addStatement("if·(%L)·%L", emit(clause.condition, depth = 0, min = 0), add)
        }
        return code.endControlFlow().build()
    }

    /** A cascader's outputs are enforced at runtime; an event the rule visibly constructs can be
     * checked right now. */
    private fun checkOutput(event: Expr) {
        val type = when (event) {
            is Expr.Construct -> event.type
            is Expr.Name -> event.name.takeIf { it.first().isUpperCase() }
            else -> null
        } ?: return
        if (type != env.output) fail(event.at, "This handler's output is ${env.output} - it can't emit $type")
    }

    // Kotlin precedences, low to high - a child that binds looser than its position needs parens.
    private fun precedence(e: Expr): Int = when (e) {
        is Expr.If -> 0
        is Expr.Binary -> when (e.op) {
            "||" -> 10; "&&" -> 20; "==", "!=" -> 30; "<", "<=", ">", ">=" -> 40; "?:" -> 50
            "+", "-" -> 70; else -> 80
        }
        is Expr.Unary -> 90
        else -> 100
    }

    private fun emit(e: Expr, depth: Int, min: Int): CodeBlock {
        val code = when (e) {
            is Expr.Num -> CodeBlock.of("%L", e.text)
            is Expr.Str -> CodeBlock.of("%S", e.value)
            is Expr.Bool -> CodeBlock.of("%L", e.value)
            is Expr.Null -> CodeBlock.of("null")
            is Expr.ListOf ->
                if (e.items.isEmpty()) CodeBlock.of("emptyList()")
                else CodeBlock.of("listOf(%L)", e.items.map { emit(it, depth, 0) }.joinToCode(",·"))
            is Expr.Name -> name(e, depth)
            is Expr.Member -> CodeBlock.of(if (e.safe) "%L?.%N" else "%L.%N", emit(e.target, depth, 100), e.name)
            is Expr.With -> {
                e.args.firstOrNull { it.name == null }?.let { fail(it.at, "`with` takes named fields: `x with (field: value)`") }
                CodeBlock.of("%L.copy(%L)", emit(e.target, depth, 100), args(e.args, depth))
            }
            is Expr.Construct -> CodeBlock.of("%T(%L)", type(e.type, e.at), args(e.args, depth))
            is Expr.Unary -> {
                val operand = emit(e.operand, depth, if (e.operand is Expr.Unary) 101 else 90)
                CodeBlock.of("%L%L", e.op, operand)
            }
            is Expr.Binary -> {
                val p = precedence(e)
                // Comparisons and equalities don't chain in Kotlin - parenthesize either side.
                val nonAssoc = p == 30 || p == 40
                CodeBlock.of(
                    "%L·%L·%L",
                    emit(e.left, depth, if (nonAssoc) p + 1 else p),
                    e.op,
                    emit(e.right, depth, p + 1),
                )
            }
            is Expr.If -> CodeBlock.of(
                "if·(%L)·%L·else·%L",
                emit(e.condition, depth, 0), emit(e.then, depth, 1), emit(e.otherwise, depth, 0),
            )
            is Expr.Pipe -> e.stages.fold(emit(e.source, depth, 100)) { receiver, stage -> stage(receiver, stage, depth) }
        }
        return if (precedence(e) < min) CodeBlock.of("(%L)", code) else code
    }

    private fun args(args: List<Arg>, depth: Int): CodeBlock = args.map { arg ->
        val value = emit(arg.value, depth, 0)
        if (arg.name == null) value else CodeBlock.of("%N·=·%L", arg.name, value)
    }.joinToCode(",·")

    private fun stage(receiver: CodeBlock, stage: Stage, depth: Int): CodeBlock {
        // Per-element operands run in a lambda, where `it` is the element.
        fun lambda(e: Expr) = emit(e, depth + 1, 0)
        val operand = stage.operand
        return when (stage.op) {
            "where" -> CodeBlock.of("%L.filter·{·%L·}", receiver, lambda(operand!!))
            "remove" -> CodeBlock.of("%L.filterNot·{·%L·}", receiver, lambda(operand!!))
            "select" -> CodeBlock.of("%L.map·{·%L·}", receiver, lambda(operand!!))
            "update" -> {
                stage.args.firstOrNull { it.name == null }?.let { fail(it.at, "`set` takes named fields: `set (field: value)`") }
                CodeBlock.of("%L.map·{·if·(%L)·it.copy(%L)·else·it·}", receiver, lambda(operand!!), args(stage.args, depth + 1))
            }
            "any", "all", "none" -> CodeBlock.of("%L.%L·{·%L·}", receiver, stage.op, lambda(operand!!))
            "count" -> optional(receiver, "count", "count", operand?.let(::lambda))
            "sum" -> optional(receiver, "sum", "sumOf", operand?.let(::lambda))
            "first" -> optional(receiver, "firstOrNull", "firstOrNull", operand?.let(::lambda))
            "last" -> optional(receiver, "lastOrNull", "lastOrNull", operand?.let(::lambda))
            "sortBy" -> CodeBlock.of("%L.sortedBy·{·%L·}", receiver, lambda(operand!!))
            "sortByDescending" -> CodeBlock.of("%L.sortedByDescending·{·%L·}", receiver, lambda(operand!!))
            "sorted" -> CodeBlock.of("%L.sorted()", receiver)
            "distinct" -> CodeBlock.of("%L.distinct()", receiver)
            "take", "drop" -> CodeBlock.of("%L.%L(%L)", receiver, stage.op, emit(operand!!, depth, 0))
            else -> fail(stage.at, "Unknown stage '${stage.op}'")
        }
    }

    private fun optional(receiver: CodeBlock, plain: String, withLambda: String, lambda: CodeBlock?) =
        if (lambda == null) CodeBlock.of("%L.%L()", receiver, plain) else CodeBlock.of("%L.%L·{·%L·}", receiver, withLambda, lambda)

    private fun name(e: Expr.Name, depth: Int): CodeBlock = when (val n = e.name) {
        "event" -> env.eventParameter?.let { CodeBlock.of("%N", it) } ?: fail(e.at, "There's no event here - an initial value can use params and literals")
        "it" -> if (depth > 0) CodeBlock.of("it") else fail(e.at, "`it` is only defined inside a pipeline stage (`list | where it.done`)")
        "params" -> if (env.params) { usesParams = true; CodeBlock.of("params") } else fail(e.at, "This scope has no params")
        in env.stateNames -> CodeBlock.of("state")
        in env.reads -> CodeBlock.of("%N.value", env.reads.getValue(n))
        "state" -> fail(e.at, "`state` is a state handler's own state - this handler cascades events; add the container to `reads` to use its value")
        else -> if (n.first().isUpperCase() && n in env.types) CodeBlock.of("%T", type(n, e.at))
        else fail(e.at, "Unknown name '$n' - this rule can use: ${available().joinToString()}")
    }

    private fun available(): List<String> =
        listOfNotNull("event".takeIf { env.eventParameter != null }) + env.stateNames + env.reads.keys + listOfNotNull("params".takeIf { env.params })

    private fun type(name: String, at: Int): ClassName {
        if (name !in env.types) fail(at, "Unknown type '$name' - the schema's types: ${env.types.keys.sorted().joinToString()}")
        return env.types[name] ?: fail(at, "'$name' names more than one type in the schema - rename one of them")
    }

    private fun fail(at: Int, message: String): Nothing = throw RuleException(source, at, message)
}
