package dev.natromacro.script

import dev.natromacro.input.CameraLedger
import dev.natromacro.input.NatroKey
import dev.natromacro.input.isMove
import dev.natromacro.input.isPitch
import dev.natromacro.input.isYaw
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

interface Actuator {
    fun keyDown(key: NatroKey)
    fun keyUp(key: NatroKey)
    fun tap(key: NatroKey, count: Int)
    /** Wall-clock milliseconds the distance actually took. */
    fun walk(tiles: Double, hasteCap: Int): Long
    fun sleep(ms: Long)
    fun gotoRamp()
    fun gotoCannon()
    fun unwindCamera()
    fun clickAt(x: Int, y: Int)
}

class ScriptEnv(
    hiveSlot: Double = 1.0,
    hiveBees: Double = 50.0,
    moveMethod: String = "Walk",
    size: Double = 1.0,
    reps: Double = 1.0,
    facingCorner: Double = 0.0,
    keyDelayMs: Double = 0.0,
) {
    var keyDelayMs: Double = keyDelayMs
    var fileTime100ns: Double = 0.0
    private val scopes = ArrayDeque<MutableMap<String, Value>>()

    init {
        scopes.addLast(HashMap())
        set("hiveslot", Value.Num(hiveSlot))
        set("hivebees", Value.Num(hiveBees))
        set("movemethod", Value.Str(moveMethod))
        set("size", Value.Num(size))
        set("reps", Value.Num(reps))
        set("facingcorner", Value.Num(facingCorner))
        fun key(name: String, value: String) = set(name, Value.Str(value))
        key("fwdkey", "Forward")
        key("tcfbkey", "Forward")
        key("leftkey", "Left")
        key("tclrkey", "Left")
        key("backkey", "Back")
        key("afcfbkey", "Back")
        key("rightkey", "Right")
        key("afclrkey", "Right")
        key("rotleft", "RotLeft")
        key("rotright", "RotRight")
        key("rotup", "RotUp")
        key("rotdown", "RotDown")
        key("zoomin", "ZoomIn")
        key("zoomout", "ZoomOut")
        key("sc_e", "E")
        key("sc_space", "Space")
        key("sc_lshift", "Shift")
        key("sc_r", "R")
        key("sc_l", "L")
        key("sc_esc", "Esc")
        key("sc_enter", "Enter")
        key("sc_1", "Hotbar1")
        key("sc_slash", "Slash")
    }

    var aIndex: Double = 1.0

    fun push() { scopes.addLast(HashMap()) }
    fun pop() { scopes.removeLast() }

    fun set(name: String, value: Value) {
        scopes.last()[name.lowercase()] = value
    }

    fun get(name: String): Value {
        val key = name.lowercase()
        if (key == "a_index") return Value.Num(aIndex)
        if (key == "a_keydelay") return Value.Num(keyDelayMs)
        for (scope in scopes.reversed()) {
            scope[key]?.let { return it }
        }
        throw IllegalStateException("unknown variable $name")
    }
}

sealed interface Value {
    data class Num(val v: Double) : Value
    data class Str(val v: String) : Value
    data class ListVal(val items: List<Value>) : Value
}

class RouteRunner(
    val actuator: Actuator,
    val env: ScriptEnv = ScriptEnv(),
) {
    private val functions = HashMap<String, Stmt.Fun>()

    fun run(program: Program, unwindCamera: Boolean = false) {
        hoist(program.body)
        exec(program.body)
        if (unwindCamera) actuator.unwindCamera()
    }

    private fun hoist(stmt: Stmt) {
        when (stmt) {
            is Stmt.Fun -> functions[stmt.name.lowercase()] = stmt
            is Stmt.Block -> stmt.items.forEach { hoist(it) }
            is Stmt.Seq -> stmt.items.forEach { hoist(it) }
            else -> Unit
        }
    }

    private fun exec(stmt: Stmt) {
        when (stmt) {
            is Stmt.Block -> stmt.items.forEach { exec(it) }
            is Stmt.Seq -> stmt.items.forEach { exec(it) }
            is Stmt.Assign -> env.set(stmt.name, eval(stmt.expr))
            is Stmt.Fun -> Unit
            is Stmt.If -> {
                if (truth(eval(stmt.cond))) exec(stmt.then) else stmt.otherwise?.let { exec(it) }
            }
            is Stmt.Loop -> {
                val n = num(eval(stmt.count)).toInt()
                if (n > 0) {
                    for (i in 1..n) {
                        env.aIndex = i.toDouble()
                        exec(stmt.body)
                    }
                }
            }
            is Stmt.Switch -> {
                val value = eval(stmt.expr)
                val hit = stmt.cases.firstOrNull { it.label != null && equals(eval(it.label), value) }
                if (hit != null) exec(hit.body)
                else stmt.cases.firstOrNull { it.label == null }?.let { exec(it.body) }
            }
            is Stmt.ForEach -> {
                val list = eval(stmt.list) as? Value.ListVal
                    ?: throw IllegalStateException("${stmt.name} is not a list")
                for (item in list.items) {
                    env.set(stmt.name, item)
                    exec(stmt.body)
                }
            }
            is Stmt.CallStmt -> call(stmt)
        }
    }

    private fun call(stmt: Stmt.CallStmt) {
        val name = stmt.name.lowercase()
        functions[name]?.let { funDef ->
            invoke(funDef, stmt.args)
            return
        }
        when (name) {
            "nm_walk" -> {
                val values = flatten(stmt.args)
                val tiles = num(values[0])
                val keys = values.drop(1).map { resolveKey(text(it)) }
                keys.forEach { actuator.keyDown(it) }
                advance(actuator.walk(tiles, 0))
                keys.forEach { actuator.keyUp(it) }
            }
            "walk" -> {
                val values = flatten(stmt.args)
                val tiles = num(values[0])
                val cap = if (values.size >= 2) num(values[1]).toInt() else 0
                advance(actuator.walk(tiles, cap))
            }
            "send", "sendinput" -> performSend(text(flatten(stmt.args)[0]))
            "sleep", "hypersleep" -> {
                val ms = num(flatten(stmt.args)[0]).toLong().coerceAtLeast(0)
                actuator.sleep(ms)
                advance(ms)
            }
            "nm_gotoramp" -> actuator.gotoRamp()
            "nm_gotocannon" -> actuator.gotoCannon()
            "setkeydelay" -> env.keyDelayMs = num(flatten(stmt.args)[0])
            "dllcall" -> {
                val fn = text(eval(stmt.args[0].expr))
                if (!fn.equals("GetSystemTimeAsFileTime", true)) {
                    throw UnknownCallException(fn, "unsupported DllCall $fn")
                }
                stmt.args.drop(1).forEach { eval(it.expr) }
            }
            else -> throw UnknownCallException(stmt.name, "unknown call ${stmt.name}")
        }
    }

    private fun invoke(funDef: Stmt.Fun, args: List<Arg>) {
        env.push()
        try {
            val values = flatten(args)
            val param = funDef.params.singleOrNull { it.variadic }
            if (param != null) {
                env.set(param.name, Value.ListVal(values))
            } else {
                funDef.params.forEachIndexed { i, p ->
                    env.set(p.name, values.getOrElse(i) { Value.Num(0.0) })
                }
            }
            exec(funDef.body)
        } finally {
            env.pop()
        }
    }

    private fun flatten(args: List<Arg>): List<Value> {
        val out = ArrayList<Value>()
        for (arg in args) {
            val value = eval(arg.expr)
            if (arg.spread) {
                val list = value as? Value.ListVal ?: throw IllegalStateException("spread of a non-list")
                out += list.items
            } else {
                out += value
            }
        }
        return out
    }

    private fun performSend(body: String) {
        val groups = SEND.findAll(body)
        if (groups.none()) return
        for (group in groups) {
            val parts = group.groupValues[1].trim().split(Regex("\\s+"))
            val key = resolveKey(parts[0])
            when {
                parts.size == 1 -> actuator.tap(key, 1)
                parts[1].equals("down", true) -> actuator.keyDown(key)
                parts[1].equals("up", true) -> actuator.keyUp(key)
                else -> actuator.tap(key, parts[1].toInt())
            }
        }
    }

    private fun advance(ms: Long) {
        env.fileTime100ns += ms.coerceAtLeast(0) * 10_000.0
    }

    private fun eval(expr: Expr): Value = when (expr) {
        is Expr.Num -> Value.Num(expr.v)
        is Expr.Str -> Value.Str(expr.v)
        is Expr.Var -> env.get(expr.name)
        is Expr.Arr -> Value.ListVal(expr.items.map { eval(it) })
        is Expr.RefInit -> {
            eval(expr.init)
            val now = Value.Num(env.fileTime100ns)
            env.set(expr.name, now)
            now
        }
        is Expr.Unary -> Value.Num(-num(eval(expr.expr)))
        is Expr.Call -> evalCall(expr)
        is Expr.Bin -> evalBin(expr)
    }

    private fun evalCall(expr: Expr.Call): Value {
        val args = flatten(expr.args)
        return when (expr.name.lowercase()) {
            "sqrt" -> Value.Num(sqrt(num(args[0])))
            "max" -> Value.Num(max(num(args[0]), num(args[1])))
            else -> {
                val funDef = functions[expr.name.lowercase()]
                    ?: throw UnknownCallException(expr.name, "unknown call ${expr.name}")
                invoke(funDef, expr.args)
                Value.Num(0.0)
            }
        }
    }

    private fun evalBin(expr: Expr.Bin): Value {
        if (expr.op == Op.OR) {
            val left = eval(expr.left)
            return if (truth(left)) left else eval(expr.right)
        }
        if (expr.op == Op.AND) {
            val left = eval(expr.left)
            return if (!truth(left)) left else eval(expr.right)
        }
        if (expr.op == Op.CONCAT) {
            return Value.Str(textOf(eval(expr.left)) + textOf(eval(expr.right)))
        }
        if (expr.op == Op.EQ || expr.op == Op.NEQ) {
            val same = equals(eval(expr.left), eval(expr.right))
            val bit = if (expr.op == Op.EQ) same else !same
            return Value.Num(if (bit) 1.0 else 0.0)
        }
        val l = num(eval(expr.left))
        val r = num(eval(expr.right))
        return when (expr.op) {
            Op.ADD -> Value.Num(l + r)
            Op.SUB -> Value.Num(l - r)
            Op.MUL -> Value.Num(l * r)
            Op.DIV -> Value.Num(l / r)
            Op.FLOOR -> Value.Num(floor(l / r))
            Op.POW -> Value.Num(Math.pow(l, r))
            Op.LT -> Value.Num(if (l < r) 1.0 else 0.0)
            Op.GT -> Value.Num(if (l > r) 1.0 else 0.0)
            Op.LE -> Value.Num(if (l <= r) 1.0 else 0.0)
            Op.GE -> Value.Num(if (l >= r) 1.0 else 0.0)
        }
    }

    private fun equals(a: Value, b: Value): Boolean = when {
        a is Value.Str || b is Value.Str -> textOf(a).equals(textOf(b), true)
        else -> num(a) == num(b)
    }

    private fun truth(v: Value): Boolean = when (v) {
        is Value.Num -> v.v != 0.0
        is Value.Str -> v.v.isNotEmpty() && v.v != "0"
        is Value.ListVal -> v.items.isNotEmpty()
    }

    private fun num(v: Value): Double = when (v) {
        is Value.Num -> v.v
        is Value.Str -> v.v.toDouble()
        is Value.ListVal -> throw IllegalStateException("list used as a number")
    }

    private fun text(v: Value): String = textOf(v)

    private fun textOf(v: Value): String = when (v) {
        is Value.Str -> v.v
        is Value.Num -> if (v.v % 1.0 == 0.0) v.v.toLong().toString() else v.v.toString()
        is Value.ListVal -> v.items.joinToString(",") { textOf(it) }
    }

    companion object {
        private val SEND = Regex("\\{([^{}]+)}")

        fun resolveKey(raw: String): NatroKey = when (raw.lowercase()) {
            "forward", "fwd", "fwdkey", "tcfbkey", "w" -> NatroKey.Forward
            "back", "backkey", "afcfbkey", "s" -> NatroKey.Back
            "left", "leftkey", "tclrkey", "a" -> NatroKey.Left
            "right", "rightkey", "afclrkey", "d" -> NatroKey.Right
            "rotleft" -> NatroKey.RotLeft
            "rotright" -> NatroKey.RotRight
            "rotup" -> NatroKey.RotUp
            "rotdown" -> NatroKey.RotDown
            "zoomin", "i" -> NatroKey.ZoomIn
            "zoomout", "o" -> NatroKey.ZoomOut
            "e", "sc_e" -> NatroKey.E
            "space", "sc_space" -> NatroKey.Space
            "shift", "sc_lshift", "lshift" -> NatroKey.Shift
            "r", "sc_r" -> NatroKey.R
            "l", "sc_l" -> NatroKey.L
            "esc", "sc_esc" -> NatroKey.Esc
            "enter", "sc_enter" -> NatroKey.Enter
            "1", "hotbar1", "sc_1" -> NatroKey.Hotbar1
            "/", "slash", "sc_slash" -> NatroKey.Slash
            "click" -> NatroKey.Click
            else -> throw IllegalStateException("unknown key $raw")
        }
    }
}

/** Records what a route asked the phone to do. Walk time uses a fixed stud speed. */
class LogActuator(var studsPerSecond: Double = 28.0) : Actuator {
    val log = ArrayList<String>()
    val held = linkedSetOf<NatroKey>()
    private val ledger = CameraLedger()
    var resetCount: Int = 0

    override fun keyDown(key: NatroKey) {
        held += key
        log += "down $key"
    }

    override fun keyUp(key: NatroKey) {
        held -= key
        log += "up $key"
    }

    override fun tap(key: NatroKey, count: Int) {
        if (key.isYaw() || key.isPitch()) ledger.apply(key, count)
        log += "tap $key x$count"
    }

    override fun walk(tiles: Double, hasteCap: Int): Long {
        val moves = held.filter { it.isMove() }.joinToString("+")
        log += "walk $tiles cap=$hasteCap keys=$moves"
        return ((tiles * 4.0 / studsPerSecond) * 1000.0).toLong()
    }

    override fun sleep(ms: Long) { log += "sleep $ms" }
    override fun gotoRamp() { log += "ramp" }
    override fun gotoCannon() { log += "cannon" }
    override fun clickAt(x: Int, y: Int) { log += "click $x,$y" }

    override fun unwindCamera() {
        for ((key, count) in ledger.unwind()) {
            log += "tap $key x$count"
        }
    }
}
