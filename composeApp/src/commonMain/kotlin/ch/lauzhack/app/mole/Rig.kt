package ch.lauzhack.app.mole

import ch.lauzhack.app.MoleState
import ch.lauzhack.app.MoleState.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

// A line-for-line port of createRig in mole/mole.js: same constants, same order of random draws, so for the same calls
// both draw the same frames (RigParityTest checks this against node).

private const val DT = 1.0 / 240
private const val SPECKS = 12
private const val GRAVITY = 300.0
private val TIP = doubleArrayOf(39.5, -22.0)

// Apple's (duration, bounce) spring with mass 1: k is stiffness, c damping.
internal enum class Ch(duration: Double, bounce: Double) {
    X(0.5, 0.2), Y(0.5, 0.2), Rot(0.5, 0.2),
    Head(0.6, 0.25),
    SpecsX(0.35, 0.1), SpecsY(0.35, 0.1),
    Card(0.45, 0.2),
    Cx(0.55, 0.2), Cy(0.55, 0.2), Crot(0.55, 0.2),
    Fan(0.45, 0.2),
    Stack(0.4, 0.1),
    Mound(0.5, 0.2),
    Breathe(0.4, 0.0), Scent(0.4, 0.0), Sniff(0.4, 0.0), Ponder(0.4, 0.0), Tunnel(0.4, 0.0),
    Ground(0.2, 0.0),
    Tie(0.7, 0.2);

    val k = (2 * PI / duration).pow(2)
    val c = 4 * PI * (1 - bounce) / duration
}

private fun pose(x: Double, y: Double, rot: Double, head: Double, specsX: Double = 0.0, specsY: Double = 0.0, card: Double = 0.0) =
    mapOf(Ch.X to x, Ch.Y to y, Ch.Rot to rot, Ch.Head to head, Ch.SpecsX to specsX, Ch.SpecsY to specsY, Ch.Card to card)

private val POSES: Map<MoleState, Map<Ch, Double>> = mapOf(
    Hidden to pose(-4.0, 126.0, 6.0, 30.0),
    Idle to pose(-10.0, 70.0, 4.0, 30.0),
    Heard to pose(0.0, 0.0, 0.0, 0.0),
    Working to pose(-12.0, 46.5, 8.0, 62.0),
    Retrieving to pose(-10.0, 30.0, 0.0, 43.0, card = 1.0) + mapOf(Ch.Cx to 53.0, Ch.Cy to -31.0, Ch.Crot to -14.0, Ch.Stack to 1.0),
    Ready to pose(-4.0, 0.0, 0.0, -2.0, card = 1.0) + mapOf(Ch.Cx to 36.0, Ch.Cy to -33.0, Ch.Crot to 0.0),
    Back to pose(-16.0, 63.0, 4.0, 58.0, card = 1.0) + mapOf(Ch.Cx to 72.0, Ch.Cy to -15.5, Ch.Crot to 0.0),
    Unsure to pose(-4.0, 20.0, 2.0, 12.0, specsX = 9.0, specsY = -5.0),
    Thinking to pose(0.0, 14.0, -4.0, -24.0),
    Waiting to pose(-10.0, 36.0, 4.0, 14.0),
    Tunneling to pose(-4.0, 126.0, 6.0, 30.0) + mapOf(Ch.Mound to 1.0),
    Failed to pose(0.0, 14.0, 2.0, 30.0),
    Hello to pose(0.0, -2.0, 0.0, -4.0),
    Choosing to pose(-2.0, 4.0, 0.0, 14.0, card = 1.0) + mapOf(Ch.Fan to 1.0, Ch.Cx to 36.0, Ch.Cy to -35.0, Ch.Crot to -4.0),
    Picked to pose(-4.0, 0.0, 0.0, -2.0, card = 1.0) + mapOf(Ch.Fan to 0.0, Ch.Cx to 36.0, Ch.Cy to -33.0, Ch.Crot to 0.0),
)
private val CARD_HOME = mapOf(Ch.Cx to 44.0, Ch.Cy to 20.0, Ch.Crot to -14.0)
private val LOOPS = mapOf(
    Heard to setOf(Ch.Breathe), Ready to setOf(Ch.Breathe), Working to setOf(Ch.Scent), Idle to setOf(Ch.Sniff),
    Thinking to setOf(Ch.Ponder), Tunneling to setOf(Ch.Tunnel), Choosing to setOf(Ch.Ponder), Picked to setOf(Ch.Breathe),
)
private val KICKS = mapOf(Unsure to mapOf(Ch.Head to -140.0), Back to mapOf(Ch.Head to 90.0), Hello to mapOf(Ch.SpecsY to -180.0), Picked to mapOf(Ch.SpecsY to -90.0))
private val SPINS = mapOf(Hello to mapOf(Ch.Tie to 360.0))
private val ENVELOPES = listOf(Ch.Breathe, Ch.Scent, Ch.Sniff, Ch.Ponder, Ch.Tunnel)

private fun bump(p: Double) = 0.5 - 0.5 * cos(2 * PI * p)
private fun rotate(x: Double, y: Double, deg: Double): DoubleArray {
    val a = deg * PI / 180
    val c = cos(a)
    val s = sin(a)
    return doubleArrayOf(x * c - y * s, x * s + y * c)
}

internal class Mulberry32(private var a: Int) {
    fun next(): Double {
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
    }
}

internal class Speck(val x: Double, val y: Double, val r: Double)

internal class Pose(
    val bodyX: Double, val bodyY: Double, val bodyRot: Double,
    val lifeX: Double, val lifeY: Double, val lifeRot: Double, val lifeSx: Double, val lifeSy: Double,
    val head: Double, val sway: Double, val yaw: Double, val nose: Double,
    val specsX: Double, val specsY: Double,
    val card: Double, val cardX: Double, val cardY: Double, val cardRot: Double, val fan: Double, val stack: Double,
    val tipX: Double, val tipY: Double,
    val scent: DoubleArray, val tie: Double, val moundX: Double, val moundY: Double, val ground: Double,
    val specks: List<Speck>,
)

internal class Rig(seed: Int) {
    private class Spring(var x: Double, val k: Double, val c: Double) {
        var v = 0.0
        var target = x
    }
    private class Flying { var x = 0.0; var y = 0.0; var vx = 0.0; var vy = 0.0; var r = 0.0 }
    private class Pending(val state: MoleState, val reduce: Boolean, val timeout: Double, val at: Double)

    private val random = Mulberry32(seed)
    private fun uniform(a: Double, b: Double) = a + (b - a) * random.next()
    private val ch = Ch.entries.map { Spring(POSES.getValue(Hidden)[it] ?: CARD_HOME[it] ?: 0.0, it.k, it.c) }
    private operator fun Ch.invoke() = ch[ordinal]
    private val specks = List(SPECKS) { Flying() }
    private var t = 0.0
    private var acc = 0.0
    private var pending: Pending? = null
    private var nextSpeck = 0.0
    private var nextSniff: Double? = null
    private var sniffStart = Double.NEGATIVE_INFINITY
    private var entered = 0.0
    private var waitFor = 0.0
    private var reduced = false
    private var shakeStart = Double.NEGATIVE_INFINITY

    init {
        Ch.Ground().x = 1.0
        Ch.Ground().target = 1.0
    }

    private fun flush() {
        val p = pending ?: return
        if (t < p.at) return
        val pose = POSES.getValue(p.state)
        val loops = LOOPS[p.state].orEmpty()
        pending = null
        entered = t
        reduced = p.reduce
        waitFor = if (p.state == Waiting) p.timeout / 1000 else 0.0
        for (name in Ch.entries) {
            val s = name()
            // tick() owns the ground line's countdown, and the tie keeps the angle it spun to so leaving hello doesn't unwind it.
            if (name != Ch.Ground && name != Ch.Tie) {
                s.target = pose[name] ?: CARD_HOME[name] ?: if (!p.reduce && name in loops) 1.0 else 0.0
            }
            if (p.reduce) { s.x = s.target; s.v = 0.0 }
        }
        if (p.reduce) {
            for (f in specks) f.r = 0.0
            shakeStart = Double.NEGATIVE_INFINITY
            return
        }
        KICKS[p.state]?.forEach { (name, dv) -> name().v += dv }
        SPINS[p.state]?.forEach { (name, d) -> name().target += d }
        // A shake under way finishes instead of restarting, so the snout never jumps back to full length mid-shake.
        if (p.state == Failed && t - shakeStart >= 1.2) shakeStart = t
    }

    private fun moundX() = 12 + 30 * Ch.Tunnel().x * sin(2 * PI * t / 2.6)

    private fun tick() {
        flush()
        val ground = Ch.Ground()
        ground.target = if (waitFor != 0.0) max(0.0, 1 - (t - entered) / waitFor) else 1.0
        // The countdown is information, not decoration, so reduced motion keeps it and only drops the spring.
        if (reduced) { ground.x = ground.target; ground.v = 0.0 }
        for (s in ch) {
            s.v += (s.k * (s.target - s.x) - s.c * s.v) * DT
            s.x += s.v * DT
        }
        if (Ch.Sniff().x > 0.01) {
            val next = nextSniff
            if (next == null) nextSniff = t + uniform(1.8, 4.2)
            else if (t >= next) { sniffStart = t; nextSniff = t + uniform(1.8, 4.2) }
        } else nextSniff = null
        // Only tunnelling throws dirt; searching sniffs along the ground instead.
        if (Ch.Tunnel().x > 0.5 && t >= nextSpeck) {
            nextSpeck = t + 0.2 + uniform(-0.05, 0.05)
            specks.firstOrNull { it.r == 0.0 }?.let { f ->
                f.x = moundX() + 18 * sign(cos(2 * PI * t / 2.6))
                f.y = 0.0
                val a = uniform(-110.0, -70.0) * PI / 180
                val speed = uniform(70.0, 95.0)
                f.vx = speed * cos(a)
                f.vy = speed * sin(a)
                f.r = uniform(1.4, 2.2)
            }
        }
        for (f in specks) {
            if (f.r == 0.0) continue
            f.vy += GRAVITY * DT
            f.x += f.vx * DT
            f.y += f.vy * DT
            if (f.y > 2 && f.vy > 0) f.r = 0.0
        }
        t += DT
    }

    fun set(state: MoleState, reduce: Boolean = false, delay: Double = 0.0, timeout: Double = 8000.0) {
        pending = Pending(state, reduce, timeout, t + delay)
        flush()
    }

    // Fixed sub-steps keep the springs stable and the check deterministic at any frame rate; the remainder carries over.
    fun step(dt: Double) {
        acc += min(0.1, max(0.0, dt))
        while (acc >= DT) { tick(); acc -= DT }
    }

    fun pose(): Pose {
        fun e(c: Ch) = c().x
        val scent = e(Ch.Scent)
        val bob = scent * bump(t / 0.8)
        val breathe = e(Ch.Breathe) * bump(t / 3.2)
        val u = t - sniffStart
        val sniffAngle = if (u >= 0 && u < 0.672) -4 * sin(PI * ((u % 0.336) / 0.336)).pow(2) else 0.0
        val sy0 = 1 + min(0.08, max(-0.06, -Ch.Y().v / 6000)) // rising (negative v) stretches, landing squashes
        val w = t - shakeStart
        val yaw = if (w < 1.2) 1 - 0.38 * exp(-3.2 * w) * abs(sin(2 * PI * w / 0.3)) else 1.0
        val h = rotate(TIP[0], TIP[1], Ch.Head().x)
        val tip = rotate(22 + h[0], -80 + h[1], Ch.Rot().x)
        return Pose(
            bodyX = Ch.X().x, bodyY = Ch.Y().x, bodyRot = Ch.Rot().x,
            lifeX = 0.6 * bob, lifeY = 1.4 * bob, lifeRot = 1.2 * bob,
            lifeSx = (1 / sqrt(sy0)) * (1 + 0.012 * breathe), lifeSy = sy0 * (1 + 0.025 * breathe),
            head = Ch.Head().x,
            sway = 5 * e(Ch.Ponder) * sin(2 * PI * t / 2.4),
            yaw = yaw,
            nose = 5 * scent * bump((t - 0.08) / 0.4) + e(Ch.Sniff) * sniffAngle,
            specsX = Ch.SpecsX().x, specsY = Ch.SpecsY().x,
            card = Ch.Card().x, cardX = Ch.Cx().x, cardY = Ch.Cy().x, cardRot = Ch.Crot().x,
            fan = Ch.Fan().x, stack = Ch.Stack().x,
            tipX = Ch.X().x + tip[0], tipY = Ch.Y().x + tip[1],
            scent = DoubleArray(2) { i -> scent * (0.2 + 0.8 * bump((t - i * 0.18) / 0.9)) }, // the two sniff marks pulse outward in turn
            tie = Ch.Tie().x,
            moundX = moundX(), moundY = 12 * (1 - Ch.Mound().x),
            ground = Ch.Ground().x,
            specks = specks.map { Speck(it.x, it.y, it.r) },
        )
    }

    val asleep: Boolean
        get() = pending == null && specks.all { it.r == 0.0 } && ENVELOPES.all { it().target == 0.0 } &&
            t - entered >= waitFor && t - shakeStart >= 1.2 && ch.all { abs(it.target - it.x) < 0.01 && abs(it.v) < 0.01 }
}
