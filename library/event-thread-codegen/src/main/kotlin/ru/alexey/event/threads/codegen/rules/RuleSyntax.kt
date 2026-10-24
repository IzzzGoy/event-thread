package ru.alexey.event.threads.codegen.rules

/**
 * The handler rule language - a small pipeline-style expression language that a handler's `rule`
 * is written in, compiled to Kotlin at generation (see [RuleCompiler]).
 *
 * ```
 * rule    := clause (';' clause)* | expr          -- clauses: cascading handlers; expr: state handlers
 * clause  := ['when' expr] 'emit' expr (',' expr)*
 * expr    := 'if' expr 'then' expr 'else' expr | elvis
 * elvis   := pipe ('?:' pipe)*
 * pipe    := or ('|' stage)*
 * stage   := 'where' or | 'remove' 'where' or | 'select' or | 'update' 'where' or 'set' '(' args ')'
 *          | 'count' [or] | 'any' or | 'all' or | 'none' or | 'sum' [or] | 'first' [or] | 'last' [or]
 *          | 'sortBy' or | 'sortByDescending' or | 'sorted' | 'distinct' | 'take' or | 'drop' or
 * or      := and (('or' | '||') and)*
 * and     := eq (('and' | '&&') eq)*
 * eq      := cmp (('==' | '!=') cmp)*
 * cmp     := add (('<' | '<=' | '>' | '>=') add)*
 * add     := mul (('+' | '-') mul)*
 * mul     := unary (('*' | '/' | '%') unary)*
 * unary   := ('not' | '!' | '-') unary | postfix
 * postfix := primary (('.' | '?.') NAME | 'with' '(' args ')')*
 * primary := NUMBER | STRING ('"..."' or '\'...\'') | 'true' | 'false' | 'null' | '[' [expr (',' expr)*] ']'
 *          | TYPE '(' [args] ')' | NAME | '(' expr ')'
 * args    := arg (',' arg)*      arg := [NAME ':'] expr   -- positional args: constructions only
 * ```
 * Stage keywords are only keywords right after `|`, so a container named `count` or `first` is
 * still a plain name elsewhere. A `TYPE` is a name starting upper-case, followed by `(`.
 */
sealed interface RuleNode {
    val at: Int
}

sealed interface Expr : RuleNode {
    data class Num(val text: String, override val at: Int) : Expr
    data class Str(val value: String, override val at: Int) : Expr
    data class Bool(val value: Boolean, override val at: Int) : Expr
    data class Null(override val at: Int) : Expr
    data class ListOf(val items: List<Expr>, override val at: Int) : Expr
    data class Name(val name: String, override val at: Int) : Expr
    /** `target.name`, or with [safe] `target?.name` (null when target is). */
    data class Member(val target: Expr, val name: String, override val at: Int, val safe: Boolean = false) : Expr
    data class With(val target: Expr, val args: List<Arg>, override val at: Int) : Expr
    data class Construct(val type: String, val args: List<Arg>, override val at: Int) : Expr
    data class Unary(val op: String, val operand: Expr, override val at: Int) : Expr
    data class Binary(val op: String, val left: Expr, val right: Expr, override val at: Int) : Expr
    data class If(val condition: Expr, val then: Expr, val otherwise: Expr, override val at: Int) : Expr
    data class Pipe(val source: Expr, val stages: List<Stage>, override val at: Int) : Expr
}

/** A named (`name: value`) or, in a construction, positional (`value`, [name] `null`) argument. */
data class Arg(val name: String?, val value: Expr, val at: Int)

/** One `| stage` of a pipeline. [operand] is evaluated per element (`it`), [args] for `update ... set`. */
data class Stage(val op: String, val operand: Expr?, val args: List<Arg> = emptyList(), val at: Int)

/** A cascading rule's `[when condition] emit events` clause. */
data class Clause(val condition: Expr?, val events: List<Expr>, val at: Int)

/** A parsed rule: [expr] for a state handler, [clauses] for a cascading one. */
data class Rule(val source: String, val expr: Expr?, val clauses: List<Clause>)

/** A rule that can't be parsed or compiled, with the position (0-based column) it's about. */
class RuleException(val rule: String, val at: Int, message: String) :
    IllegalArgumentException("$message\n  $rule\n  ${" ".repeat(at.coerceIn(0, rule.length))}^")
