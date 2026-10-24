package ru.alexey.event.threads.codegen.rules

/** Parses the rule language (grammar: see [RuleNode]). */
object RuleParser {

    private enum class Kind { NAME, NUMBER, STRING, SYMBOL, END }

    private data class Token(val kind: Kind, val text: String, val at: Int)

    private val KEYWORDS = setOf("if", "then", "else", "when", "emit", "with", "and", "or", "not", "true", "false", "null")
    private val STAGES = setOf(
        "where", "remove", "select", "update", "count", "any", "all", "none", "sum", "first", "last",
        "sortBy", "sortByDescending", "sorted", "distinct", "take", "drop",
    )
    private val SYMBOLS = listOf("?.", "?:", "==", "!=", "<=", ">=", "&&", "||", "<", ">", "!", "+", "-", "*", "/", "%", "|", ".", ",", ":", ";", "(", ")", "[", "]")

    fun parse(source: String): Rule = Parser(source, tokenize(source)).rule()

    private fun tokenize(src: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    out += Token(Kind.NAME, src.substring(start, i), start)
                }
                c.isDigit() -> {
                    val start = i
                    while (i < src.length && src[i].isDigit()) i++
                    if (i + 1 < src.length && src[i] == '.' && src[i + 1].isDigit()) {
                        i++
                        while (i < src.length && src[i].isDigit()) i++
                    }
                    out += Token(Kind.NUMBER, src.substring(start, i), start)
                }
                // '...' too: a rule lives in a JSON string, where "..." needs escaping.
                c == '"' || c == '\'' -> {
                    val quote = c
                    val start = i++
                    val text = StringBuilder()
                    while (i < src.length && src[i] != quote) {
                        if (src[i] == '\\' && i + 1 < src.length) { text.append(src[i + 1]); i += 2 } else text.append(src[i++])
                    }
                    if (i >= src.length) throw RuleException(src, start, "Unterminated string")
                    i++
                    out += Token(Kind.STRING, text.toString(), start)
                }
                else -> {
                    val symbol = SYMBOLS.firstOrNull { src.startsWith(it, i) }
                        ?: throw RuleException(src, i, "Unexpected character '$c'")
                    out += Token(Kind.SYMBOL, symbol, i)
                    i += symbol.length
                }
            }
        }
        out += Token(Kind.END, "", src.length)
        return out
    }

    private class Parser(val src: String, val tokens: List<Token>) {
        var pos = 0
        val peek get() = tokens[pos]
        fun next() = tokens[pos++]
        fun isSymbol(s: String) = peek.kind == Kind.SYMBOL && peek.text == s
        fun isWord(w: String) = peek.kind == Kind.NAME && peek.text == w
        fun fail(message: String, at: Int = peek.at): Nothing = throw RuleException(src, at, message)
        fun expectSymbol(s: String) { if (!isSymbol(s)) fail("Expected '$s'") ; next() }
        fun expectWord(w: String) { if (!isWord(w)) fail("Expected '$w'"); next() }

        fun rule(): Rule {
            if (peek.kind == Kind.END) fail("Empty rule")
            val isCascade = isWord("emit") || isWord("when")
            val result = if (isCascade) {
                val clauses = mutableListOf(clause())
                while (isSymbol(";")) { next(); if (peek.kind != Kind.END) clauses += clause() }
                Rule(src, null, clauses)
            } else {
                Rule(src, expr(), emptyList())
            }
            if (peek.kind != Kind.END) fail("Unexpected '${peek.text}'")
            return result
        }

        fun clause(): Clause {
            val at = peek.at
            val condition = if (isWord("when")) { next(); expr() } else null
            expectWord("emit")
            val events = mutableListOf(expr())
            while (isSymbol(",")) { next(); events += expr() }
            return Clause(condition, events, at)
        }

        fun expr(): Expr {
            if (isWord("if")) {
                val at = next().at
                val c = expr(); expectWord("then")
                val a = expr(); expectWord("else")
                return Expr.If(c, a, expr(), at)
            }
            var left = pipe()
            while (isSymbol("?:")) { val at = next().at; left = Expr.Binary("?:", left, pipe(), at) }
            return left
        }

        fun pipe(): Expr {
            val source = or()
            if (!isSymbol("|")) return source
            val stages = mutableListOf<Stage>()
            while (isSymbol("|")) { next(); stages += stage() }
            return Expr.Pipe(source, stages, source.at)
        }

        fun stage(): Stage {
            val t = peek
            if (t.kind != Kind.NAME || t.text !in STAGES) fail("Expected a pipeline stage (${STAGES.joinToString()})")
            next()
            // An optional operand is there when what follows can start one - `state | count` then
            // `)` / `|` / `then` / `==` ... has none.
            val endsOperand = {
                when (peek.kind) {
                    Kind.NUMBER, Kind.STRING -> false
                    Kind.NAME -> peek.text in KEYWORDS && peek.text !in setOf("not", "true", "false", "null", "if")
                    Kind.SYMBOL -> peek.text !in setOf("(", "[", "!")
                    Kind.END -> true
                }
            }
            return when (t.text) {
                "remove" -> { expectWord("where"); Stage("remove", or(), at = t.at) }
                "update" -> {
                    expectWord("where")
                    val condition = or()
                    expectWord("set")
                    Stage("update", condition, args(), t.at)
                }
                "sorted", "distinct" -> Stage(t.text, null, at = t.at)
                "count", "sum", "first", "last" -> Stage(t.text, if (endsOperand()) null else or(), at = t.at)
                else -> Stage(t.text, or(), at = t.at)
            }
        }

        fun args(): List<Arg> {
            expectSymbol("(")
            val out = mutableListOf<Arg>()
            if (!isSymbol(")")) {
                do {
                    if (out.isNotEmpty()) next()
                    val at = peek.at
                    val named = peek.kind == Kind.NAME && tokens[pos + 1].let { it.kind == Kind.SYMBOL && it.text == ":" }
                    out += if (named) {
                        val name = next().text
                        next()
                        Arg(name, expr(), at)
                    } else {
                        Arg(null, expr(), at)
                    }
                } while (isSymbol(","))
            }
            expectSymbol(")")
            return out
        }

        private fun binary(ops: Map<String, String>, operand: () -> Expr): Expr {
            var left = operand()
            while (true) {
                val op = ops[peek.text]?.takeIf { peek.kind == Kind.SYMBOL || peek.kind == Kind.NAME } ?: return left
                val at = next().at
                left = Expr.Binary(op, left, operand(), at)
            }
        }

        fun or(): Expr = binary(mapOf("or" to "||", "||" to "||")) { and() }
        fun and(): Expr = binary(mapOf("and" to "&&", "&&" to "&&")) { eq() }
        fun eq(): Expr = binary(mapOf("==" to "==", "!=" to "!=")) { cmp() }
        fun cmp(): Expr = binary(mapOf("<" to "<", "<=" to "<=", ">" to ">", ">=" to ">=")) { add() }
        fun add(): Expr = binary(mapOf("+" to "+", "-" to "-")) { mul() }
        fun mul(): Expr = binary(mapOf("*" to "*", "/" to "/", "%" to "%")) { unary() }

        fun unary(): Expr {
            if (isWord("not") || isSymbol("!")) { val at = next().at; return Expr.Unary("!", unary(), at) }
            if (isSymbol("-")) { val at = next().at; return Expr.Unary("-", unary(), at) }
            return postfix()
        }

        fun postfix(): Expr {
            var e = primary()
            while (true) {
                e = when {
                    isSymbol(".") || isSymbol("?.") -> {
                        val safe = next().text == "?."
                        val name = next()
                        if (name.kind != Kind.NAME) fail("Expected a field name after '.'", name.at)
                        Expr.Member(e, name.text, name.at, safe)
                    }
                    isWord("with") -> { val at = next().at; Expr.With(e, args(), at) }
                    else -> return e
                }
            }
        }

        fun primary(): Expr {
            val t = peek
            return when {
                t.kind == Kind.NUMBER -> { next(); Expr.Num(t.text, t.at) }
                t.kind == Kind.STRING -> { next(); Expr.Str(t.text, t.at) }
                isWord("true") || isWord("false") -> { next(); Expr.Bool(t.text == "true", t.at) }
                isWord("null") -> { next(); Expr.Null(t.at) }
                isSymbol("[") -> {
                    next()
                    val items = mutableListOf<Expr>()
                    if (!isSymbol("]")) { items += expr(); while (isSymbol(",")) { next(); items += expr() } }
                    expectSymbol("]")
                    Expr.ListOf(items, t.at)
                }
                isSymbol("(") -> { next(); val e = expr(); expectSymbol(")"); e }
                t.kind == Kind.NAME && t.text !in KEYWORDS -> {
                    next()
                    if (t.text.first().isUpperCase() && isSymbol("(")) {
                        val args = if (tokens[pos + 1].let { it.kind == Kind.SYMBOL && it.text == ")" }) { next(); next(); emptyList() } else args()
                        Expr.Construct(t.text, args, t.at)
                    } else {
                        Expr.Name(t.text, t.at)
                    }
                }
                t.kind == Kind.END -> fail("Unexpected end of rule")
                else -> fail("Unexpected '${t.text}'")
            }
        }
    }
}
