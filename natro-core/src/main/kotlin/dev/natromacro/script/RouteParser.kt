package dev.natromacro.script

class RouteParseException(message: String) : IllegalArgumentException(message)

class UnknownCallException(val call: String, message: String) : IllegalArgumentException(message)

data class Param(val name: String, val variadic: Boolean)

sealed interface Expr {
    data class Num(val v: Double) : Expr
    data class Str(val v: String) : Expr
    data class Var(val name: String) : Expr
    data class Bin(val op: Op, val left: Expr, val right: Expr) : Expr
    data class Unary(val op: Op, val expr: Expr) : Expr
    data class Call(val name: String, val args: List<Arg>) : Expr
    data class Arr(val items: List<Expr>) : Expr
    data class RefInit(val name: String, val init: Expr) : Expr
}

data class Arg(val expr: Expr, val spread: Boolean = false)

enum class Op { ADD, SUB, MUL, DIV, FLOOR, POW, EQ, NEQ, LT, GT, LE, GE, OR, AND, CONCAT }

sealed interface Stmt {
    data class Assign(val name: String, val expr: Expr) : Stmt
    data class CallStmt(val name: String, val args: List<Arg>, val line: Int) : Stmt
    data class Seq(val items: List<Stmt>) : Stmt
    data class Block(val items: List<Stmt>) : Stmt
    data class If(val cond: Expr, val then: Stmt, val otherwise: Stmt?) : Stmt
    data class Loop(val count: Expr, val body: Stmt) : Stmt
    data class Switch(val expr: Expr, val cases: List<Case>) : Stmt
    data class ForEach(val name: String, val list: Expr, val body: Stmt) : Stmt
    data class Fun(val name: String, val params: List<Param>, val body: Stmt) : Stmt
}

data class Case(val label: Expr?, val body: Stmt)

data class Program(val name: String, val body: Stmt)

private sealed interface Tok {
    val line: Int
    data class Ident(override val line: Int, val text: String) : Tok
    data class Num(override val line: Int, val v: Double) : Tok
    data class Str(override val line: Int, val v: String) : Tok
    data class Sym(override val line: Int, val kind: Kind) : Tok
    data class Eof(override val line: Int) : Tok
}

private enum class Kind {
    NL, PLUS, MINUS, STAR, POW, SLASH, FLOOR, ASSIGN, EQ, NEQ, LT, GT, LE, GE, OR, AND,
    LP, RP, LB, RB, LS, RS, COMMA, COLON, AMP,
}

private class Lexer(private val src: String) {
    private val tokens = ArrayList<Tok>()
    private var i = 0
    private var line = 1

    fun tokenize(): List<Tok> {
        if (src.startsWith("\uFEFF")) i = 1
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\r' -> i++
                c == '\n' -> { tokens += Tok.Sym(line, Kind.NL); line++; i++ }
                c == ' ' || c == '\t' -> i++
                c == ';' -> while (i < src.length && src[i] != '\n') i++
                c == '"' -> string()
                c.isDigit() -> number()
                c == '.' && i + 1 < src.length && src[i + 1].isDigit() -> number()
                c.isLetter() || c == '_' -> ident()
                else -> symbol()
            }
        }
        tokens += Tok.Eof(line)
        return tokens
    }

    private fun string() {
        val start = line
        i++
        val sb = StringBuilder()
        while (i < src.length && src[i] != '"') {
            sb.append(src[i])
            i++
        }
        if (i >= src.length) throw RouteParseException("unterminated string at line $start")
        i++
        tokens += Tok.Str(start, sb.toString())
    }

    private fun number() {
        val start = i
        val startLine = line
        while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
        tokens += Tok.Num(startLine, src.substring(start, i).toDouble())
    }

    private fun ident() {
        val start = i
        val startLine = line
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
        tokens += Tok.Ident(startLine, src.substring(start, i))
    }

    private fun symbol() {
        val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
        val kind = when {
            two == "**" -> { i += 2; Kind.POW }
            two == "//" -> { i += 2; Kind.FLOOR }
            two == ":=" -> { i += 2; Kind.ASSIGN }
            two == "!=" -> { i += 2; Kind.NEQ }
            two == "<=" -> { i += 2; Kind.LE }
            two == ">=" -> { i += 2; Kind.GE }
            two == "||" -> { i += 2; Kind.OR }
            two == "&&" -> { i += 2; Kind.AND }
            else -> {
                val k = when (src[i]) {
                    '+' -> Kind.PLUS
                    '-' -> Kind.MINUS
                    '*' -> Kind.STAR
                    '/' -> Kind.SLASH
                    '=' -> Kind.EQ
                    '<' -> Kind.LT
                    '>' -> Kind.GT
                    '(' -> Kind.LP
                    ')' -> Kind.RP
                    '{' -> Kind.LB
                    '}' -> Kind.RB
                    '[' -> Kind.LS
                    ']' -> Kind.RS
                    ',' -> Kind.COMMA
                    ':' -> Kind.COLON
                    '&' -> Kind.AMP
                    else -> throw RouteParseException("unexpected '${src[i]}' at line $line")
                }
                i++
                k
            }
        }
        tokens += Tok.Sym(line, kind)
    }
}

class RouteParser {
    fun parse(source: String, name: String): Program {
        val tokens = Lexer(source).tokenize()
        val p = Parser(tokens, name)
        val program = p.parseProgram(name)
        validate(program)
        return program
    }

    private fun validate(program: Program) {
        val functions = HashSet<String>()
        collectFuns(program.body, functions)
        checkStmt(program.body, functions, program.name)
    }

    private fun collectFuns(stmt: Stmt, out: MutableSet<String>) {
        when (stmt) {
            is Stmt.Fun -> out += stmt.name.lowercase()
            is Stmt.Block -> stmt.items.forEach { collectFuns(it, out) }
            is Stmt.Seq -> stmt.items.forEach { collectFuns(it, out) }
            is Stmt.If -> { collectFuns(stmt.then, out); stmt.otherwise?.let { collectFuns(it, out) } }
            is Stmt.Loop -> collectFuns(stmt.body, out)
            is Stmt.Switch -> stmt.cases.forEach { collectFuns(it.body, out) }
            is Stmt.ForEach -> collectFuns(stmt.body, out)
            else -> Unit
        }
    }

    private fun checkStmt(stmt: Stmt, functions: Set<String>, file: String) {
        when (stmt) {
            is Stmt.CallStmt -> {
                val n = stmt.name.lowercase()
                if (n !in STMT_CALLS && n !in functions) {
                    throw UnknownCallException(stmt.name, "$file:${stmt.line}: unknown call ${stmt.name}")
                }
                if (n == "dllcall") {
                    val first = stmt.args.firstOrNull()?.expr
                    val ok = first is Expr.Str && first.v.equals("GetSystemTimeAsFileTime", true)
                    if (!ok) throw UnknownCallException("DllCall", "$file:${stmt.line}: only GetSystemTimeAsFileTime is supported")
                }
                stmt.args.forEach { checkExpr(it.expr, functions, file, stmt.line) }
            }
            is Stmt.Assign -> checkExpr(stmt.expr, functions, file, 0)
            is Stmt.Block -> stmt.items.forEach { checkStmt(it, functions, file) }
            is Stmt.Seq -> stmt.items.forEach { checkStmt(it, functions, file) }
            is Stmt.If -> {
                checkExpr(stmt.cond, functions, file, 0)
                checkStmt(stmt.then, functions, file)
                stmt.otherwise?.let { checkStmt(it, functions, file) }
            }
            is Stmt.Loop -> { checkExpr(stmt.count, functions, file, 0); checkStmt(stmt.body, functions, file) }
            is Stmt.Switch -> {
                checkExpr(stmt.expr, functions, file, 0)
                stmt.cases.forEach {
                    it.label?.let { label -> checkExpr(label, functions, file, 0) }
                    checkStmt(it.body, functions, file)
                }
            }
            is Stmt.ForEach -> { checkExpr(stmt.list, functions, file, 0); checkStmt(stmt.body, functions, file) }
            is Stmt.Fun -> checkStmt(stmt.body, functions, file)
        }
    }

    private fun checkExpr(expr: Expr, functions: Set<String>, file: String, line: Int) {
        when (expr) {
            is Expr.Call -> {
                val n = expr.name.lowercase()
                if (n !in EXPR_CALLS && n !in functions) {
                    throw UnknownCallException(expr.name, "$file:$line: unknown call ${expr.name}")
                }
                expr.args.forEach { checkExpr(it.expr, functions, file, line) }
            }
            is Expr.Bin -> { checkExpr(expr.left, functions, file, line); checkExpr(expr.right, functions, file, line) }
            is Expr.Unary -> checkExpr(expr.expr, functions, file, line)
            is Expr.Arr -> expr.items.forEach { checkExpr(it, functions, file, line) }
            is Expr.RefInit -> checkExpr(expr.init, functions, file, line)
            else -> Unit
        }
    }

    companion object {
        val STMT_CALLS = setOf(
            "nm_walk", "walk", "send", "sendinput", "sleep", "hypersleep",
            "nm_gotoramp", "nm_gotocannon", "setkeydelay", "dllcall",
        )
        val EXPR_CALLS = setOf("sqrt", "max")
    }
}

private class Parser(private val tokens: List<Tok>, private val file: String) {
    private var p = 0

    fun parseProgram(name: String): Program {
        val items = ArrayList<Stmt>()
        while (true) {
            skipNl()
            if (peek() is Tok.Eof) break
            items += parseLine()
        }
        return Program(name, Stmt.Block(items))
    }

    private fun parseLine(): Stmt {
        val first = parseOne()
        if (!isSym(Kind.COMMA)) return first
        val items = mutableListOf(first)
        while (isSym(Kind.COMMA)) {
            consume()
            items += parseOne()
        }
        return Stmt.Seq(items)
    }

    private fun parseOne(): Stmt {
        if (isIdent("if")) return parseIf()
        if (isIdent("loop")) return parseLoop()
        if (isIdent("switch")) return parseSwitch()
        if (isIdent("for")) return parseFor()
        if (looksLikeFun()) return parseFun()
        if (peek() is Tok.Ident && nextIs(Kind.ASSIGN)) {
            val name = (consume() as Tok.Ident).text
            consume()
            return Stmt.Assign(name, parseExpr())
        }
        if (peek() is Tok.Ident) {
            val id = consume() as Tok.Ident
            val args = when {
                isSym(Kind.LP) -> {
                    consume()
                    parseArgs()
                }
                startsExpr() -> listOf(Arg(parseExpr()))
                else -> emptyList()
            }
            return Stmt.CallStmt(id.text, args, id.line)
        }
        throw err("expected statement")
    }

    private fun parseIf(): Stmt {
        consume()
        val cond = parseExpr()
        skipNl()
        val then = if (isSym(Kind.LB)) parseBlock() else parseLine()
        skipNl()
        val otherwise = if (isIdent("else")) {
            consume()
            skipNl()
            if (isSym(Kind.LB)) parseBlock() else parseLine()
        } else null
        return Stmt.If(cond, then, otherwise)
    }

    private fun parseLoop(): Stmt {
        consume()
        val count = parseExpr()
        skipNl()
        val body = if (isSym(Kind.LB)) parseBlock() else parseLine()
        return Stmt.Loop(count, body)
    }

    private fun parseSwitch(): Stmt {
        consume()
        val expr = parseExpr()
        skipNl()
        expect(Kind.LB)
        val cases = ArrayList<Case>()
        while (true) {
            skipNl()
            when {
                isSym(Kind.RB) -> { consume(); break }
                isIdent("case") -> {
                    consume()
                    val label = parseExpr()
                    expect(Kind.COLON)
                    cases += Case(label, parseUntilBoundary())
                }
                isIdent("default") -> {
                    consume()
                    expect(Kind.COLON)
                    cases += Case(null, parseUntilBoundary())
                }
                peek() is Tok.Eof -> throw err("unclosed switch")
                else -> throw err("expected case or default")
            }
        }
        return Stmt.Switch(expr, cases)
    }

    private fun parseUntilBoundary(): Stmt {
        val items = ArrayList<Stmt>()
        while (true) {
            skipNl()
            if (isSym(Kind.RB) || isIdent("case") || isIdent("default") || peek() is Tok.Eof) break
            items += parseLine()
        }
        return Stmt.Block(items)
    }

    private fun parseFor(): Stmt {
        consume()
        val name = (consume() as? Tok.Ident ?: throw err("expected loop variable")).text
        if (!isIdent("in")) throw err("expected in")
        consume()
        val list = parseExpr()
        skipNl()
        val body = if (isSym(Kind.LB)) parseBlock() else parseLine()
        return Stmt.ForEach(name, list, body)
    }

    private fun parseFun(): Stmt {
        val name = (consume() as Tok.Ident).text
        expect(Kind.LP)
        val params = ArrayList<Param>()
        if (!isSym(Kind.RP)) {
            while (true) {
                val n = (consume() as? Tok.Ident ?: throw err("expected parameter")).text
                val variadic = isSym(Kind.STAR) && (tokenAt(1).let { it is Tok.Sym && (it.kind == Kind.COMMA || it.kind == Kind.RP) })
                if (variadic) consume()
                params += Param(n, variadic)
                if (isSym(Kind.COMMA)) consume() else break
            }
        }
        expect(Kind.RP)
        skipNl()
        return Stmt.Fun(name, params, parseBlock())
    }

    private fun parseBlock(): Stmt {
        expect(Kind.LB)
        val items = ArrayList<Stmt>()
        while (true) {
            skipNl()
            if (isSym(Kind.RB)) { consume(); break }
            if (peek() is Tok.Eof) throw err("unclosed block")
            items += parseLine()
        }
        return Stmt.Block(items)
    }

    private fun parseArgs(): List<Arg> {
        val args = ArrayList<Arg>()
        skipNl()
        if (isSym(Kind.RP)) { consume(); return args }
        while (true) {
            skipNl()
            args += parseArg()
            skipNl()
            if (isSym(Kind.COMMA)) { consume(); continue }
            break
        }
        expect(Kind.RP)
        return args
    }

    private fun parseArg(): Arg {
        if (peek() is Tok.Ident && nextIs(Kind.STAR)) {
            val after = tokenAt(2)
            if (after is Tok.Sym && (after.kind == Kind.COMMA || after.kind == Kind.RP)) {
                val name = (consume() as Tok.Ident).text
                consume()
                return Arg(Expr.Var(name), spread = true)
            }
        }
        return Arg(parseExpr())
    }

    private fun parseExpr(): Expr = parseOr()

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (isSym(Kind.OR)) { consume(); left = Expr.Bin(Op.OR, left, parseAnd()) }
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseCmp()
        while (isSym(Kind.AND)) { consume(); left = Expr.Bin(Op.AND, left, parseCmp()) }
        return left
    }

    private fun parseCmp(): Expr {
        var left = parseAdd()
        val op = cmpOp() ?: return left
        consume()
        return Expr.Bin(op, left, parseAdd())
    }

    private fun parseAdd(): Expr {
        var left = parseMul()
        while (true) {
            left = when {
                isSym(Kind.PLUS) -> { consume(); Expr.Bin(Op.ADD, left, parseMul()) }
                isSym(Kind.MINUS) -> { consume(); Expr.Bin(Op.SUB, left, parseMul()) }
                else -> return left
            }
        }
    }

    private fun parseMul(): Expr {
        var left = parsePow()
        while (true) {
            left = when {
                isSym(Kind.STAR) -> { consume(); Expr.Bin(Op.MUL, left, parsePow()) }
                isSym(Kind.SLASH) -> { consume(); Expr.Bin(Op.DIV, left, parsePow()) }
                isSym(Kind.FLOOR) -> { consume(); Expr.Bin(Op.FLOOR, left, parsePow()) }
                else -> return left
            }
        }
    }

    private fun parsePow(): Expr {
        val left = parseUnary()
        if (!isSym(Kind.POW)) return left
        consume()
        return Expr.Bin(Op.POW, left, parsePow())
    }

    private fun parseUnary(): Expr {
        if (isSym(Kind.MINUS)) { consume(); return Expr.Unary(Op.SUB, parseUnary()) }
        return parseConcat()
    }

    private fun parseConcat(): Expr {
        var left = parsePrimary()
        while (sameLinePrimary()) {
            left = Expr.Bin(Op.CONCAT, left, parsePrimary())
        }
        return left
    }

    private fun parsePrimary(): Expr {
        val t = peek()
        when (t) {
            is Tok.Num -> { consume(); return Expr.Num(t.v) }
            is Tok.Str -> { consume(); return Expr.Str(t.v) }
            is Tok.Ident -> {
                consume()
                if (isSym(Kind.LP)) {
                    consume()
                    return Expr.Call(t.text, parseArgs())
                }
                return Expr.Var(t.text)
            }
            else -> Unit
        }
        if (isSym(Kind.LP)) {
            consume()
            val e = parseExpr()
            expect(Kind.RP)
            return e
        }
        if (isSym(Kind.LS)) {
            consume()
            val items = ArrayList<Expr>()
            skipNl()
            if (!isSym(Kind.RS)) {
                while (true) {
                    items += parseExpr()
                    skipNl()
                    if (isSym(Kind.COMMA)) { consume(); skipNl(); continue }
                    break
                }
            }
            expect(Kind.RS)
            return Expr.Arr(items)
        }
        if (isSym(Kind.AMP)) {
            consume()
            val name = (consume() as? Tok.Ident ?: throw err("expected name")).text
            expect(Kind.ASSIGN)
            return Expr.RefInit(name, parseExpr())
        }
        throw err("expected expression")
    }

    private fun looksLikeFun(): Boolean {
        if (peek() !is Tok.Ident) return false
        var i = 1
        if (tokenAt(i) !is Tok.Sym || (tokenAt(i) as Tok.Sym).kind != Kind.LP) return false
        var depth = 0
        while (p + i < tokens.size) {
            val t = tokens[p + i]
            if (t is Tok.Sym && t.kind == Kind.LP) depth++
            if (t is Tok.Sym && t.kind == Kind.RP) {
                depth--
                if (depth == 0) {
                    var j = i + 1
                    while (p + j < tokens.size && tokens[p + j] is Tok.Sym && (tokens[p + j] as Tok.Sym).kind == Kind.NL) j++
                    val next = tokens.getOrNull(p + j)
                    return next is Tok.Sym && next.kind == Kind.LB
                }
            }
            if (t is Tok.Eof) return false
            i++
        }
        return false
    }

    private fun sameLinePrimary(): Boolean {
        val a = peek()
        val prev = tokens.getOrNull(p - 1) ?: return false
        if (a.line != prev.line) return false
        return a is Tok.Ident || a is Tok.Str || a is Tok.Num || (a is Tok.Sym && (a.kind == Kind.LP || a.kind == Kind.LS))
    }

    private fun startsExpr(): Boolean {
        val t = peek()
        return t is Tok.Num || t is Tok.Str || t is Tok.Ident ||
            (t is Tok.Sym && (t.kind == Kind.LP || t.kind == Kind.LS || t.kind == Kind.MINUS || t.kind == Kind.AMP))
    }

    private fun cmpOp(): Op? = when {
        isSym(Kind.EQ) -> Op.EQ
        isSym(Kind.NEQ) -> Op.NEQ
        isSym(Kind.LE) -> Op.LE
        isSym(Kind.GE) -> Op.GE
        isSym(Kind.LT) -> Op.LT
        isSym(Kind.GT) -> Op.GT
        else -> null
    }

    private fun skipNl() { while (isSym(Kind.NL)) consume() }

    private fun isIdent(text: String): Boolean {
        val t = peek()
        return t is Tok.Ident && t.text.equals(text, true)
    }

    private fun isSym(kind: Kind): Boolean {
        val t = peek()
        return t is Tok.Sym && t.kind == kind
    }

    private fun nextIs(kind: Kind): Boolean {
        val t = tokenAt(1)
        return t is Tok.Sym && t.kind == kind
    }

    private fun expect(kind: Kind) {
        if (!isSym(kind)) throw err("expected $kind")
        consume()
    }

    private fun peek(): Tok = tokens[p]
    private fun tokenAt(offset: Int): Tok = tokens.getOrElse(p + offset) { Tok.Eof(peek().line) }
    private fun consume(): Tok = tokens[p++]
    private fun err(message: String): RouteParseException =
        RouteParseException("$file:${peek().line}: $message")
}
