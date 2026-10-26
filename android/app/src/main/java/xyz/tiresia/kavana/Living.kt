package xyz.tiresia.kavana

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Living cats: the painted pictures bent live, so they breathe, blink, flick an ear, swish a tail,
// tilt their head, look at your finger and hop when you tap them. No new art. Every picture has a rig
// (assets/rig.json: eyes, head, neck, ears, tail and ground line, in the picture's 512 px) and a mesh
// that moves. The same rig and the same numbers as the web app's warp.js (kavana-apps/motion), so
// both apps move alike. Drawn with Canvas.drawBitmapMesh, in Compose (LiveCat) and over other apps.

@Serializable
data class CatRig(
    val ground: Float, val mid: Float, val top: Float, val w: Float,
    val eyes: List<List<Float>>? = null, val head: List<Float>? = null, val neck: List<Float>? = null,
    val ears: List<List<Float>?>? = null, val tail: List<Float>? = null,
)

object Rigs {
    @Volatile private var all: Map<String, CatRig>? = null

    fun get(ctx: Context, key: String): CatRig? = (all ?: synchronized(this) {
        all ?: runCatching {
            val text = ctx.assets.open("rig.json").bufferedReader().use { it.readText() }
            KJson.decodeFromString(MapSerializer(String.serializer(), CatRig.serializer()), text)
        }.getOrDefault(emptyMap()).also { all = it }
    })[key]
}

/** The motion numbers (motion/warp.js M): damping ratios, settle times in seconds, sizes in the picture's px. */
object Motion {
    const val BREATH_PERIOD = 3.6f; const val BREATH_IN = 0.42f; const val BREATH_TALL = 0.03f; const val BREATH_BELLY = 0.05f
    const val NAP_PERIOD = 5.2f; const val NAP_IN = 0.45f; const val NAP_TALL = 0.05f; const val NAP_BELLY = 0.03f
    const val BLINK_MIN = 2.2f; const val BLINK_MAX = 5.5f; const val BLINK_CLOSE = 0.07f; const val BLINK_HOLD = 0.04f
    const val BLINK_OPEN = 0.12f; const val BLINK_TWICE = 0.2f; const val BLINK_DEPTH = 0.94f
    const val HEAD_DRIFT = 0.044f; const val HEAD_DAMPING = 0.55f; const val HEAD_SETTLE = 0.8f; const val HEAD_TILT = 0.12f
    const val TILT_MIN = 7f; const val TILT_MAX = 14f; const val TILT_FOR = 1.4f
    const val LOOK_MAX = 16f; const val LOOK_DAMPING = 0.8f; const val LOOK_SETTLE = 0.35f
    const val EAR_KICK = 5.5f; const val EAR_DAMPING = 0.28f; const val EAR_SETTLE = 0.45f; const val EAR_MIN = 4f; const val EAR_MAX = 9f
    const val TAIL_SWING = 0.14f; const val TAIL_PERIOD = 2.8f; const val TAIL_DAMPING = 0.35f; const val TAIL_SETTLE = 0.6f
    const val LEAN = 0.006f; const val LEAN_PERIOD = 5.3f
    const val SQUASH_DAMPING = 0.42f; const val SQUASH_SETTLE = 0.55f
    const val HOP_HEIGHT = 28f; const val HOP_TIME = 0.34f; const val HOP_CROUCH = 0.09f; const val HOP_LAND = -3.2f; const val HOP_TAKEOFF = 2.2f
    const val MORPH_DOWN = 0.13f; const val MORPH_DEPTH = -0.2f; const val MORPH_POP = 2.8f
    const val PURR_HZ = 24f; const val PURR_PX = 0.7f; const val PURR_SQUINT = 0.85f

    /** The canvas around the 512 px picture: room to hop and swish. */
    const val FX0 = -48f; const val FY0 = -80f; const val FW = 608f; const val FH = 608f
}

/** A damped spring toward `to`. damping 1 = no overshoot; settle = seconds to come within ~2%. */
class Spring(private val z: Float, settle: Float, var x: Float = 0f) {
    private val w = 4f / (z * settle)
    var v = 0f
    var to = x
    fun step(dt: Float): Float {
        val n = max(1, ceil(dt * 240f).toInt())
        val h = dt / n
        repeat(n) { v += (-2f * z * w * v - w * w * (x - to)) * h; x += v * h }
        return x
    }
}

private fun ss(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

/** How much each mesh point follows each part of the cat. Worked out once per picture and mesh size. */
class Weights(r: CatRig, val g: Int) {
    val n = (g + 1) * (g + 1)
    val x0 = FloatArray(n); val y0 = FloatArray(n); val bell = FloatArray(n); val nap = FloatArray(n)
    val head: FloatArray? = if (r.head != null && r.neck != null) FloatArray(n) else null
    val pivotX: Float; val pivotY: Float
    val earW: Array<FloatArray?> = arrayOf(null, null); val earBx = FloatArray(2); val earBy = FloatArray(2)
    val tail: FloatArray? = if (r.tail != null) FloatArray(n) else null
    val eyeW: List<FloatArray>; val eyeDy: List<FloatArray>

    init {
        val g0 = r.ground; val hh = g0 - r.top
        val hd = r.head; val nk = r.neck
        pivotX = nk?.get(0) ?: 0f
        pivotY = if (hd != null && nk != null) hd[1] + 0.6f * (nk[1] - hd[1]) else 0f // the head turns about its chin
        r.ears?.forEachIndexed { k, e -> if (e != null && hd != null && k < 2) { earW[k] = FloatArray(n); earBx[k] = e[0] + (hd[0] - e[0]) * 0.42f; earBy[k] = e[1] + (hd[1] - e[1]) * 0.42f } }
        val eyes = r.eyes.orEmpty()
        eyeW = eyes.map { FloatArray(n) }; eyeDy = eyes.map { FloatArray(n) }
        for (i in 0 until n) {
            val x = (i % (g + 1)).toFloat() / g * 512f; val y = (i / (g + 1)).toFloat() / g * 512f
            x0[i] = x; y0[i] = y
            val up = (g0 - y) / hh
            bell[i] = exp(-(((up - 0.36f) / 0.2f).let { it * it }))
            nap[i] = exp(-(((up - 0.5f) / 0.35f).let { it * it }))
            if (head != null && hd != null && nk != null) {
                // The head turns as one piece: everything above the neck within reach of the head (ears,
                // whiskers, a hat), blending into the body only across the neck. A tail beside it stays put.
                val rx = hd[2]; val ry = hd[3]
                var w = (1 - ss(nk[1] - 0.15f * ry, nk[1] + 0.3f * ry, y)) * (1 - ss(1.25f, 1.8f, abs(x - hd[0]) / rx))
                r.tail?.let { t -> w *= ss(max(0.3f * len(t), 24f), max(0.75f * len(t), 90f), offTail(t, x, y)) }
                head[i] = w
            }
            for (k in 0..1) {
                val ew = earW[k] ?: continue
                val t = r.ears!![k]!!
                val l = hypot(t[0] - earBx[k], t[1] - earBy[k])
                // an ear turns nearly stiff about its base; only the last bit near the head bends
                ew[i] = (1 - ss(0.65f * l, 1.25f * l, hypot(x - t[0], y - t[1]))) * (1 - ss(earBy[k] - 0.1f * l, earBy[k] + 0.3f * l, y))
            }
            if (tail != null) {
                val t = r.tail!!
                val dx = t[2] - t[0]; val dy = t[3] - t[1]; val l2 = dx * dx + dy * dy; val l = sqrt(l2)
                val s = ((x - t[0]) * dx + (y - t[1]) * dy) / l2
                tail[i] = if (s <= 0) 0f else ss(0f, 1.1f, min(s, 1.1f)) * (1 - ss(0.3f * l, 0.85f * l, offTail(t, x, y)))
            }
            eyes.forEachIndexed { k, e ->
                val (ex, ey, rx, ry) = e
                eyeW[k][i] = (1 - ss(1.0f, 1.6f, abs(x - ex) / rx)) * (1 - ss(1.05f, 2.2f, abs(y - ey) / ry))
                eyeDy[k][i] = ey + 0.22f * ry - y // the lids meet a little below the middle
            }
        }
    }

    private fun len(t: List<Float>) = hypot(t[2] - t[0], t[3] - t[1])
    private fun offTail(t: List<Float>, x: Float, y: Float): Float {
        val dx = t[2] - t[0]; val dy = t[3] - t[1]; val l2 = dx * dx + dy * dy
        val s = (((x - t[0]) * dx + (y - t[1]) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (t[0] + dx * s), y - (t[1] + dy * s))
    }

    companion object {
        private val cache = HashMap<String, Weights>()
        fun of(key: String, r: CatRig, g: Int): Weights = synchronized(cache) { cache.getOrPut("$key:$g") { Weights(r, g) } }
    }
}

/** One frame's pose. */
class Pose {
    var k = 0f; val ears = FloatArray(2); var tail = 0f; var th = 0f; var hx = 0f; var hy = 0f
    var bv = 0f; var bx = 0f; var squash = 0f; var nap = false; var lean = 0f; var jx = 0f; var hop = 0f
}

/** Moves every mesh point for one frame, into `out` in canvas px (the frame scaled by sx, sy). */
fun deform(out: FloatArray, r: CatRig, w: Weights, p: Pose, sx: Float, sy: Float) {
    val g = r.ground; val m = r.mid
    val ct = cos(p.tail); val st = sin(p.tail)
    val tb = r.tail
    for (i in 0 until w.n) {
        var x = w.x0[i]; var y = w.y0[i]
        if (p.k > 0.001f) for (e in w.eyeW.indices) y += w.eyeDy[e][i] * p.k * w.eyeW[e][i]
        for (k in 0..1) {
            val ew = w.earW[k] ?: continue
            val a = p.ears[k] * ew[i]
            if (a == 0f) continue
            val c = cos(a); val s = sin(a); val dx = x - w.earBx[k]; val dy = y - w.earBy[k]
            x = w.earBx[k] + dx * c - dy * s; y = w.earBy[k] + dx * s + dy * c
        }
        if (w.tail != null && tb != null && p.tail != 0f) {
            val tw = w.tail[i]
            if (tw > 0f) {
                val a = p.tail * tw
                val c = if (tw == 1f) ct else cos(a); val s = if (tw == 1f) st else sin(a)
                val dx = x - tb[0]; val dy = y - tb[1]
                x = tb[0] + dx * c - dy * s; y = tb[1] + dx * s + dy * c
            }
        }
        if (w.head != null) {
            val hw = w.head[i]
            if (hw > 0f) {
                val a = p.th * hw; val c = cos(a); val s = sin(a); val dx = x - w.pivotX; val dy = y - w.pivotY
                x = w.pivotX + dx * c - dy * s + p.hx * hw; y = w.pivotY + dx * s + dy * c + p.hy * hw
            }
        }
        val bell = if (p.nap) w.nap[i] else w.bell[i]
        y = g - (g - y) * (1 + p.bv)
        x = m + (x - m) * (1 + p.bx * bell)
        y = g - (g - y) * (1 + p.squash)
        x = m + (x - m) * (1 - 0.5f * p.squash)
        x += p.lean * (g - y) + p.jx
        y -= p.hop
        out[i * 2] = (x - Motion.FX0) * sx
        out[i * 2 + 1] = (y - Motion.FY0) * sy
    }
}

/**
 * One living cat: its state, one step of time (update) and one frame (draw), on any Canvas whose
 * w x h is the frame around the picture (Motion.FX0..). `live` off: it only breathes (Reduce motion).
 */
class LivingCat(private val ctx: Context, id: String, pose: String, seed: Int = Random.nextInt(), val grid: Int = 32, var shadow: Boolean = true, var live: Boolean = true) {
    private val r = Random(seed)
    var id = id; private set
    var pose = pose; private set
    private var key = ""; private var path = ""
    private var rig: CatRig? = null
    private var weights: Weights? = null

    private val squash = Spring(Motion.SQUASH_DAMPING, Motion.SQUASH_SETTLE)
    private val head = Spring(Motion.HEAD_DAMPING, Motion.HEAD_SETTLE)
    private val lx = Spring(Motion.LOOK_DAMPING, Motion.LOOK_SETTLE); private val ly = Spring(Motion.LOOK_DAMPING, Motion.LOOK_SETTLE)
    val ear = arrayOf(Spring(Motion.EAR_DAMPING, Motion.EAR_SETTLE), Spring(Motion.EAR_DAMPING, Motion.EAR_SETTLE))
    private val tailS = Spring(Motion.TAIL_DAMPING, Motion.TAIL_SETTLE)
    private val squint = Spring(1f, 0.18f)
    private var t = 0f
    private val ph = r.nextFloat() * 10f
    private fun between(a: Float, b: Float) = a + (b - a) * r.nextFloat()
    private var blinkAt = between(0.8f, 3.5f); private var blinkT = -1f; private var blinkTwice = false
    private var earAt = between(Motion.EAR_MIN, Motion.EAR_MAX); private var tiltAt = between(Motion.TILT_MIN, Motion.TILT_MAX)
    private var tiltUntil = 0f; private var tiltTo = 0f
    private var hopT = -1f; private var hops = 0
    private var morphT = -1f; private var next: Pair<String, String>? = null
    private var happyUntil = 0f
    /** Held: eyes shut, head leaning in, a shiver, hearts. */
    var purring = false
    private var purrHeart = 0f
    /** Where to look, in the picture's px; null: straight ahead. */
    var look: Pair<Float, Float>? = null
    /** A breath led from outside (the mindful minute): -0.5 out .. 0.5 in, breathed `guideAmp` times deeper, eyes half shut. */
    var guide: Float? = null
    var guideAmp = 3f
    private var zAt = 1f + r.nextFloat() * 1.5f
    private val fx = ArrayList<Fx>()
    val pose0 = Pose()
    private var lastW = 1f; private var lastH = 1f
    private var held: Bitmap? = null // the picture being drawn, kept even if the cache lets it go

    private class Fx(val kind: Int, var x: Float, var y: Float, var vx: Float = 0f, var vy: Float = -90f, var t: Float = 0f)

    init { set(id, pose) }

    fun set(id: String, pose: String) {
        this.id = id; this.pose = pose
        key = keyOf(id, pose); path = "cats/$key.webp"; held = null
        rig = Rigs.get(ctx, key)
        weights = rig?.let { Weights.of(key, it, grid) }
        Art.prefetch(ctx, path)
    }

    // ----- things a cat does -----
    fun hop(times: Int = 1) { if (pose == "nap") { squash.v -= 1.6f; heart(); return }; hopT = t; hops = times }
    fun happy(sec: Float = 0.7f) { happyUntil = t + sec; flick(0); flick(1); tailS.v += 3f }
    fun flick(k: Int) { ear[k].v += (if (k == 1) 1 else -1) * Motion.EAR_KICK * (0.8f + 0.4f * r.nextFloat()) }
    fun tilt(a: Float, sec: Float = Motion.TILT_FOR) { tiltTo = a; tiltUntil = t + sec }
    fun blink() { blinkT = t; blinkTwice = false }
    fun heart() { val rg = rig ?: return; fx.add(Fx(HEART, rg.mid + (r.nextFloat() - 0.5f) * 120f, rg.top + 40f, (r.nextFloat() - 0.5f) * 30f)) }
    fun sparkle(n: Int = 7) {
        val rg = rig ?: return
        val cx = rg.mid; val cy = (rg.top + rg.ground) / 2; val rr = 0.42f * rg.w
        repeat(n) { i ->
            val a = i.toFloat() / n * 2 * PI.toFloat() + r.nextFloat()
            fx.add(Fx(SPARK, cx + cos(a) * rr, cy + sin(a) * rr * 0.9f, cos(a) * 220f, sin(a) * 200f - 80f))
        }
    }
    fun poke() { hop(1); happy(); heart() }
    /** Another picture (nap, kitten, grown) as one squash and pop, never a cross-fade. */
    fun morph(id: String, pose: String) {
        if (id == this.id && pose == this.pose && next == null) return
        next = id to pose; morphT = t
        Art.prefetch(ctx, "cats/${keyOf(id, pose)}.webp")
    }
    /** Look toward a point in the canvas (px, of the w x h last drawn). */
    fun lookAtCanvas(x: Float, y: Float) { look = (Motion.FX0 + x / lastW * Motion.FW) to (Motion.FY0 + y / lastH * Motion.FH) }

    fun update(dtIn: Float) {
        val dt = dtIn.coerceIn(0f, 0.05f)
        t += dt
        val rg = rig
        var over: Float? = null
        if (morphT >= 0) {
            val u = (t - morphT) / Motion.MORPH_DOWN
            val nx = next
            if (u < 1 && (held ?: Art.cached(path)) != null) over = Motion.MORPH_DEPTH * u * u
            else if (nx != null && Art.cached("cats/${keyOf(nx.first, nx.second)}.webp") != null) {
                set(nx.first, nx.second); next = null; morphT = -1f
                squash.x = Motion.MORPH_DEPTH; squash.v = Motion.MORPH_POP; sparkle(); blinkAt = t + 0.6f
            }
        }
        var hop = 0f
        if (hopT >= 0) {
            val u = t - hopT; val c = Motion.HOP_CROUCH; val tt = Motion.HOP_TIME
            when {
                u < c -> over = -0.1f * sin(u / c * PI.toFloat() / 2)
                u < c + tt -> { val f = (u - c) / tt; hop = 4 * Motion.HOP_HEIGHT * f * (1 - f); if (squash.v < 1 && f < 0.2f) squash.v = Motion.HOP_TAKEOFF }
                else -> { squash.v += Motion.HOP_LAND; hops--; hopT = if (hops > 0) t + 0.05f else -1f }
            }
        }
        if (over != null) { squash.x = over; squash.v = 0f } else squash.step(dt)

        var k = 0f
        if (rg?.eyes != null && live) {
            if (t >= blinkAt && blinkT < 0) { blinkT = t; blinkTwice = r.nextFloat() < Motion.BLINK_TWICE }
            if (blinkT >= 0 && t >= blinkT) {
                val u = t - blinkT; val total = Motion.BLINK_CLOSE + Motion.BLINK_HOLD + Motion.BLINK_OPEN
                k = when {
                    u < Motion.BLINK_CLOSE -> (u / Motion.BLINK_CLOSE).let { it * it }
                    u < Motion.BLINK_CLOSE + Motion.BLINK_HOLD -> 1f
                    u < total -> 1 - ss(0f, 1f, (u - Motion.BLINK_CLOSE - Motion.BLINK_HOLD) / Motion.BLINK_OPEN)
                    else -> 0f
                } * Motion.BLINK_DEPTH
                if (u >= total) { blinkT = if (blinkTwice) t + 0.06f else -1f; blinkTwice = false; blinkAt = t + between(Motion.BLINK_MIN, Motion.BLINK_MAX) }
            }
        }
        if (rg?.eyes != null) {
            squint.to = if (purring) Motion.PURR_SQUINT else if (t < happyUntil) 0.72f else if (guide != null) 0.6f else 0f
            k = max(k, squint.step(dt))
        }
        if (live && rg?.ears != null && t >= earAt) { flick(if (r.nextBoolean()) 0 else 1); earAt = t + between(Motion.EAR_MIN, Motion.EAR_MAX) }
        ear.forEach { it.step(dt) }
        if (live && rg?.head != null && t >= tiltAt) { tilt((if (r.nextBoolean()) -1 else 1) * Motion.HEAD_TILT); tiltAt = t + between(Motion.TILT_MIN, Motion.TILT_MAX) }
        var th = if (live) Motion.HEAD_DRIFT * (0.6f * sin(t * 2 * PI.toFloat() / 6.1f + ph) + 0.4f * sin(t * 2 * PI.toFloat() / 3.7f + 2 * ph)) else 0f
        var tx = 0f; var ty = 0f
        val lk = look; val hd = rg?.head
        if (lk != null && hd != null) {
            val dx = lk.first - hd[0]; val dy = lk.second - hd[1]; val d = max(1f, hypot(dx, dy)); val m = min(Motion.LOOK_MAX, d * 0.08f)
            tx = dx / d * m; ty = dy / d * m * 0.6f; th += (dx / 512f).coerceIn(-1f, 1f) * 0.08f
        }
        if (purring) th += 0.1f
        head.to = th + if (t < tiltUntil) tiltTo else 0f
        head.step(dt); lx.to = tx; ly.to = ty; lx.step(dt); ly.step(dt)
        tailS.step(dt)
        val tail = if (live) Motion.TAIL_SWING * sin(t * 2 * PI.toFloat() / Motion.TAIL_PERIOD + ph) * (0.65f + 0.35f * sin(t * 2 * PI.toFloat() / 7.3f)) + tailS.x * 0.4f else 0f
        val napping = pose == "nap"
        val period = if (napping) Motion.NAP_PERIOD else Motion.BREATH_PERIOD
        val inhale = if (napping) Motion.NAP_IN else Motion.BREATH_IN
        val p = ((t + ph) / period) % 1f
        val g = guide
        val br = g ?: ((if (p < inhale) ss(0f, 1f, p / inhale) else 1 - ss(0f, 1f, (p - inhale) / (1 - inhale))) - 0.5f)
        val amp = (if (g != null) guideAmp else 1f) * (if (live) 1f else 0.4f)
        with(pose0) {
            this.k = k; ears[0] = ear[0].x; ears[1] = ear[1].x; this.tail = tail; this.th = head.x; hx = lx.x; hy = ly.x
            bv = (if (napping) Motion.NAP_TALL else Motion.BREATH_TALL) * br * amp
            bx = (if (napping) Motion.NAP_BELLY else Motion.BREATH_BELLY) * br * amp
            squash = this@LivingCat.squash.x; nap = napping
            lean = if (live) Motion.LEAN * sin(t * 2 * PI.toFloat() / Motion.LEAN_PERIOD + ph) else 0f
            jx = if (purring) Motion.PURR_PX * sin(t * 2 * PI.toFloat() * Motion.PURR_HZ) else 0f
            this.hop = hop
        }
        if (purring) { purrHeart -= dt; if (purrHeart < 0) { heart(); purrHeart = 0.55f } }
        if (napping && live && rg != null) { zAt -= dt; if (zAt < 0) { fx.add(Fx(ZED, rg.mid + 70f, rg.top + 30f, 0f, 0f)); zAt = 1.7f } }
        val it = fx.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.t += dt
            if (f.kind != ZED) { f.x += f.vx * dt; f.y += f.vy * dt; if (f.kind == SPARK) f.vy += 300f * dt }
            if (f.t > when (f.kind) { ZED -> 2.2f; SPARK -> 0.6f; else -> 1.3f }) it.remove()
        }
    }

    private val verts = FloatArray((grid + 1) * (grid + 1) * 2)
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(0f, 0f, 1f, Color.argb(255, 40, 30, 20), Color.argb(0, 40, 30, 20), Shader.TileMode.CLAMP) }
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heartPath = Path().apply { moveTo(0f, 3f); cubicTo(-9f, -4f, -5f, -11f, 0f, -6f); cubicTo(5f, -11f, 9f, -4f, 0f, 3f); close() }
    private val starPath = Path().apply { for (i in 0 until 8) { val rr = if (i % 2 == 1) 0.35f else 1f; val a = i * PI.toFloat() / 4; if (i == 0) moveTo(cos(a) * rr, sin(a) * rr) else lineTo(cos(a) * rr, sin(a) * rr) }; close() }

    /** Draws this frame on `c`, whose w x h is the frame around the picture. */
    fun draw(c: Canvas, w: Float, h: Float, alpha: Float = 1f) {
        lastW = w; lastH = h
        val rg = rig ?: return
        val wt = weights ?: return
        val bmp: Bitmap = held ?: Art.cached(path)?.also { held = it } ?: return
        val sx = w / Motion.FW; val sy = h / Motion.FH
        if (shadow) {
            val lift = (1 - pose0.hop / 90f).coerceIn(0.4f, 1f)
            val rx = 0.36f * rg.w * lift * sx; val ry = 0.05f * rg.w * lift * sy
            c.save(); c.translate((rg.mid - Motion.FX0) * sx, (rg.ground - 4 - Motion.FY0) * sy); c.scale(rx, ry)
            shadowPaint.alpha = (0.2f * lift * alpha * 255).toInt(); c.drawCircle(0f, 0f, 1f, shadowPaint); c.restore()
        }
        deform(verts, rg, wt, pose0, sx, sy)
        meshPaint.alpha = (alpha * 255).toInt()
        c.drawBitmapMesh(bmp, grid, grid, verts, 0, null, 0, meshPaint)
        for (f in fx) {
            val x = (f.x - Motion.FX0) * sx; val y = (f.y - Motion.FY0) * sy
            when (f.kind) {
                HEART -> {
                    val a = 1 - ss(0.7f, 1.3f, f.t); val k = (14f + 8f * ss(0f, 0.25f, f.t)) * sx
                    fxPaint.color = Color.rgb(240, 96, 122); fxPaint.alpha = (a * alpha * 255).toInt()
                    c.save(); c.translate(x, y); c.scale(k / 10f, k / 10f); c.drawPath(heartPath, fxPaint); c.restore()
                }
                SPARK -> {
                    val a = 1 - f.t / 0.6f; val k = 9f * sx * (1 - f.t)
                    fxPaint.color = Color.rgb(255, 201, 77); fxPaint.alpha = (a.coerceIn(0f, 1f) * alpha * 255).toInt()
                    c.save(); c.translate(x, y); c.scale(k, k); c.drawPath(starPath, fxPaint); c.restore()
                }
                ZED -> {
                    val a = ss(0f, 0.3f, f.t) * (1 - ss(1.4f, 2.2f, f.t))
                    fxPaint.color = Color.rgb(125, 143, 168); fxPaint.alpha = (a * 0.8f * alpha * 255).toInt()
                    fxPaint.typeface = Typeface.DEFAULT_BOLD; fxPaint.textSize = (20f + f.t * 14f) * sx
                    c.drawText("z", x + f.t * 26f * sx, y - f.t * 60f * sy, fxPaint)
                }
            }
        }
    }

    companion object {
        private const val HEART = 0; private const val SPARK = 1; private const val ZED = 2
        // The rig's key is the painting's name (rig.json follows a repainted cat's new number, like Art).
        fun keyOf(id: String, pose: String) = when (pose) { "kitten" -> Art.kitten(id); "nap" -> Art.nap(id); else -> Art.portrait(id) }
            .substringAfterLast('/').removeSuffix(".webp")
    }
}
