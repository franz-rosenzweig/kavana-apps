package xyz.tiresia.kavana.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import android.graphics.Canvas as ACanvas
import android.graphics.Color as AColor
import android.graphics.Paint as APaint
import android.graphics.Path as APath

// The Cat Café in 3D, ported from the web's cafe3d.js: a floating room you can spin, with the cats that
// came home in it, on the things you bought. The camera is orthographic, looking down 60° (the floor
// squashes by K, one tile of height rises by KZ), and spins in quarter turns when you let go.

private const val K = 0.5
private const val KZ = 0.866
private const val Q = PI / 2
private fun clamp(x: Double, a: Double, b: Double) = max(a, min(b, x))
private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
private fun backOut(t: Double): Double { val c1 = 1.70158; val c3 = c1 + 1; return 1 + c3 * (t - 1) * (t - 1) * (t - 1) + c1 * (t - 1) * (t - 1) }

/** The web's rng(seed): xorshift32, the same numbers for the same seed. */
private fun rng(seed: Long): () -> Double {
    var s = seed.toInt().let { if (it == 0) 1 else it }
    return { s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5); (s.toLong() and 0xffffffffL) / 4294967296.0 }
}
/** The web's hash(str). */
fun cafeHash(str: String): Long = str.fold(7L) { h, c -> (h * 31 + c.code) and 0xffffffffL }

private fun rgba(r: Int, g: Int, b: Int, a: Double) = AColor.argb((a * 255).roundToInt(), r, g, b)
private fun hx(s: String) = AColor.parseColor(s)
private fun shade(c: Int, f: Double): Int {
    fun s(x: Int) = (if (f < 0) x * (1 + f) else x + (255 - x) * f).roundToInt().coerceIn(0, 255)
    return AColor.rgb(s(AColor.red(c)), s(AColor.green(c)), s(AColor.blue(c)))
}

/** What the café draws (the web's cafeData). */
data class CafeCat(
    val key: String, val id: String, val src: String, val napSrc: String? = null, val kitten: Boolean = false,
    val poses: Map<String, String>? = null, val ghost: Boolean = false, val title: String = "", val sub: String = "",
)

data class CafeScene(
    val size: String, val seed: Long, val theme: String, val light: String, val dim: Boolean,
    val things: List<String>, val views: Map<String, Map<String, String>>, val cats: List<CafeCat>,
    val residents: List<CafeCat> = emptyList(), val gone: List<String> = emptyList(), val quiet: Boolean = true,
)

private class C3Theme(val wall: Pair<Int, Int>, val dots: Int, val skirt: Int, val skirtLine: Int, val floor: Pair<Int, Int>, val board: Int, val slab: Int, val trim: Int)

private val THEMES = mapOf(
    "classic" to C3Theme(hx("#fdf5e8") to hx("#f5e4cb"), rgba(196, 150, 104, .32), hx("#ecd2ae"), hx("#d9b386"), hx("#e8bd85") to hx("#dba56b"), rgba(124, 74, 34, .17), hx("#b9845a"), hx("#f6dcb6")),
    "mint" to C3Theme(hx("#e8f6ee") to hx("#d3ebde"), rgba(60, 140, 100, .24), hx("#cfe3d6"), hx("#a9c9b5"), hx("#ead4ab") to hx("#dcc191"), rgba(120, 85, 40, .15), hx("#a48a66"), hx("#f1e5c9")),
    "sakura" to C3Theme(hx("#fde9f0") to hx("#f8d5e2"), rgba(225, 120, 155, .36), hx("#f3c9d6"), hx("#e3a2b7"), hx("#f2ddc9") to hx("#e5c9b0"), rgba(150, 90, 70, .13), hx("#c99c88"), hx("#fbe8dd")),
    "library" to C3Theme(hx("#31604c") to hx("#284f3e"), rgba(255, 230, 180, .16), hx("#5b3b26"), hx("#462c1c"), hx("#7c5438") to hx("#684430"), rgba(0, 0, 0, .22), hx("#4d3322"), hx("#8c6748")),
    "garden" to C3Theme(hx("#eaf7ff") to hx("#cfe9fb"), rgba(255, 255, 255, .85), hx("#8fcf7a"), hx("#76b862"), hx("#9fd480") to hx("#86c266"), rgba(60, 120, 40, .16), hx("#7b9a5a"), hx("#d8efc6")),
    "night" to C3Theme(hx("#232849") to hx("#2d3257"), rgba(255, 240, 180, .6), hx("#3a3550"), hx("#4c4568"), hx("#4f3d2e") to hx("#403126"), rgba(0, 0, 0, .28), hx("#2e2622"), hx("#5d4f43")),
)

private class C3Light(val tint: Int? = null, val glow: Int? = null, val r: Double = 0.0)
private val LIGHTS = mapOf(
    "morning" to C3Light(tint = rgba(160, 200, 255, .12)),
    "day" to C3Light(),
    "evening" to C3Light(tint = rgba(255, 120, 40, .16), glow = rgba(255, 214, 150, .28), r = 1.9),
    "night" to C3Light(tint = rgba(18, 22, 66, .3), glow = rgba(255, 200, 120, .34), r = 2.0),
)

/** The light by the clock: morning, day, evening (lamps glow), night. */
fun cafeLightAt(t: Long = System.currentTimeMillis()): String {
    val h = java.util.Calendar.getInstance().apply { timeInMillis = t }.get(java.util.Calendar.HOUR_OF_DAY)
    return if (h in 5..9) "morning" else if (h in 10..16) "day" else if (h in 17..20) "evening" else "night"
}

private class C3Place(val u: Double, val v: Double, val w: Double, val fp: Pair<Double, Double>? = null, val perch: Double? = null, val light: Double? = null, val pri: Int)
private val PLACE = linkedMapOf(
    "cattree" to C3Place(0.12, 0.12, 1.05, perch = 0.93, pri = 1),
    "cushion" to C3Place(0.47, 0.5, 0.95, perch = 0.2, pri = 1),
    "box" to C3Place(0.8, 0.8, 1.0, perch = 0.48, pri = 1),
    "armchair" to C3Place(0.15, 0.8, 1.3, fp = 1.05 to 1.2, perch = 0.42, pri = 1),
    "counter" to C3Place(0.6, 0.12, 2.05, fp = 2.1 to 1.1, perch = 0.6, pri = 2),
    "fireplace" to C3Place(0.1, 0.48, 1.35, fp = 0.9 to 1.45, light = 0.35, pri = 2),
    "bookshelf" to C3Place(0.9, 0.1, 0.95, perch = 0.97, pri = 3),
    "lamp" to C3Place(0.09, 0.3, 0.72, light = 0.9, pri = 4),
    "plant" to C3Place(0.9, 0.48, 0.84, pri = 5),
    "fishbowl" to C3Place(0.9, 0.3, 0.64, pri = 6),
    "yarnbasket" to C3Place(0.43, 0.88, 0.72, pri = 6),
    "jackolantern" to C3Place(0.64, 0.34, 0.82, light = 0.6, pri = 7),
    "cauldron" to C3Place(0.3, 0.66, 0.8, light = 0.5, pri = 7),
    "snowman" to C3Place(0.72, 0.56, 0.85, pri = 7),
    "candles" to C3Place(0.88, 0.66, 0.42, light = 0.7, pri = 8),
    "sled" to C3Place(0.56, 0.9, 1.1, fp = 1.15 to 0.8, pri = 8),
    "snowglobe" to C3Place(0.32, 0.3, 0.5, pri = 9),
)
private class C3WallPlace(val edge: Char, val u: Double, val v: Double, val w: Double)
private val WALL = mapOf("window" to C3WallPlace('N', 0.33, 0.3, 1.5), "clock" to C3WallPlace('W', 0.68, 0.42, 0.62))
private val SOFT = setOf("cushion", "box", "armchair")
private val EDGES = mapOf('N' to (doubleArrayOf(0.0, 0.0) to doubleArrayOf(1.0, 0.0)), 'W' to (doubleArrayOf(0.0, 0.0) to doubleArrayOf(0.0, 1.0)),
    'E' to (doubleArrayOf(1.0, 0.0) to doubleArrayOf(1.0, 1.0)), 'S' to (doubleArrayOf(0.0, 1.0) to doubleArrayOf(1.0, 1.0)))
private val SIZES = mapOf("day" to (4 to 8), "week" to (6 to 22), "month" to (8 to 38), "big" to (10 to 60))
private val ORDER = listOf("day", "week", "month", "big")
private val POSE_W = mapOf("walk" to 0.98, "loaf" to 0.82, "stretch" to 1.05, "wash" to 0.8, "back" to 0.78)

/** A painting trimmed to its painted part, at half size (the café draws small). */
private class C3Trim(val bmp: Bitmap, val src: Rect)

private object CafeArt {
    private val ready = ConcurrentHashMap<String, C3Trim>()
    private val bad = ConcurrentHashMap.newKeySet<String>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newSingleThreadExecutor()

    fun isBad(path: String) = path in bad
    fun get(ctx: Context, path: String): C3Trim? {
        ready[path]?.let { return it }
        if (path in bad || !loading.add(path)) return null
        val app = ctx.applicationContext
        pool.execute {
            try {
                val bmp = app.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                if (bmp == null) { bad.add(path); return@execute }
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
                var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
                var y = 0
                while (y < h) { var x = 0; while (x < w) { if ((px[y * w + x] ushr 24) > 30) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }; x += 2 }; y += 2 }
                val r = if (x1 > x0) Rect(x0, y0, min(w, x1 + 2), min(h, y1 + 2)) else Rect(0, 0, w, h)
                ready[path] = C3Trim(bmp, r)
            } catch (e: Exception) { bad.add(path) } finally { loading.remove(path) }
        }
        return null
    }
}

private class C3Pt(val x: Float, val y: Float, val d: Double)

/** Something standing in the room: a thing you own or a cat. Mutable, like the web's plain objects. */
private class C3Spr(var src: String, var w: Double) {
    var cat = false; var key = ""; var id = ""; var title = ""; var sub = ""; var ghost = false
    var base = ""; var napSrc: String? = null; var nap = false; var kitten = false
    var x = 0.0; var y = 0.0; var z = 0.0; var seat: C3Spr? = null; var walker = false
    var phase = 0.0; var pop = 1.0; var wait = 0.0; var flip = false
    var poses: Map<String, String>? = null; var posed: String? = null; var to: DoubleArray? = null; var toWindow = false
    var hop = 0.0; var lean = 0.0; var squash = 0.0; var step = 0.0; var fall = false; var vz = 0.0; var didLand = false
    var hopT = -1.0; var text: String? = null; var wiggle = 0.0; var rect: RectF? = null; var d = 0.0
    // things
    var fp: Pair<Double, Double>? = null; var perch: Double? = null; var light: Double? = null; var pri = 0
}

private class C3WallItem(val id: String?, val edge: Char, val u: Double, val v: Double = 0.0, val w: Double = 0.0, val door: Boolean = false)
private class C3Print(val x: Double, val y: Double, val a: Double, val side: Int)
private class C3Fx(val kind: Int, val x: Double, val y: Double, val z: Double, val a: Double = 0.0, val sp: Double = 0.0, val max: Double, val size: Double = 0.0, val color: Int = 0, val text: String = "") { var life = 0.0; var r = 0.0 }

data class CafeTip(val title: String, val sub: String, val x: Float, val y: Float)

/** The camera and the room together (the web's View and Cafe). */
class Cafe3D(private val ctx: Context, private val dens: Float, private val reduce: Boolean) {
    // camera
    var angle = PI / 4; var goal = angle; private var spinV = 0.0
    private var S = 0.0; private var ox = 0.0; private var oy = 0.0; private var t = Random.nextDouble() * 10
    private var W = 0.0; private var H = 0.0; private var ca = cos(angle); private var sa = sin(angle)
    var dragging = false; var margin = 12.0
    private val fx = mutableListOf<C3Fx>()
    private var tipFor: C3Spr? = null; private var tipUntil = 0L; private var tipTitle = ""; private var tipSub = ""
    var names: Map<String, Pair<String, String>> = emptyMap()
    var onTip: (CafeTip?) -> Unit = {}
    // room
    private var n = 6; private var cx = 3.0; private var cy = 3.0
    private var theme = "classic"; private var light = "day"; private var dim = false
    private val wallH = 2.25; private val depth = 0.5; private val rim = 0.14
    private var things = mutableListOf<C3Spr>(); private var walls = mutableListOf<C3WallItem>(); private var cats = mutableListOf<C3Spr>()
    private var prints = mutableListOf<C3Print>(); private var rug: DoubleArray? = null; private var views: Map<String, Map<String, String>> = emptyMap()
    private var sig = ""

    private val fill = APaint(APaint.ANTI_ALIAS_FLAG).apply { style = APaint.Style.FILL }
    private val line = APaint(APaint.ANTI_ALIAS_FLAG).apply { style = APaint.Style.STROKE }
    private val bmpPaint = APaint(APaint.ANTI_ALIAS_FLAG or APaint.FILTER_BITMAP_FLAG)
    private val path = APath()
    private val mat = Matrix()

    /** Whether every painting the café shows right now has loaded (they load in the background): a picture drawn once, offscreen, waits for it. */
    fun artReady(): Boolean {
        val paths = things.map { it.src } + cats.flatMap { listOfNotNull(it.src, it.napSrc) } + walls.mapNotNull { w -> w.id?.let { "cafe/$it-1.webp" } } +
            listOfNotNull(if (rug != null) "cafe/rug-1.webp" else null)
        return paths.all { CafeArt.get(ctx, it) != null || CafeArt.isBad(it) }
    }

    // ---------- the camera ----------
    fun resize(w: Float, h: Float) { if (w.toDouble() != W || h.toDouble() != H) { W = w.toDouble(); H = h.toDouble(); S = 0.0; fit(0.0) } }

    private fun fit(dt: Double) {
        if (W <= 0 || H <= 0) return
        val r = (n / 2.0 + rim) * sqrt(2.0)
        val x0 = -r; val x1 = r; val y0 = -r * K - wallH * KZ - 0.15; val y1 = r * K + (depth + 0.35) * KZ
        val m = margin * dens
        val s = min((W - 2 * m) / (x1 - x0), (H - 2 * m) / (y1 - y0))
        val k = if (dt > 0 && !reduce) 1 - exp(-dt * 7) else 1.0
        S = if (S != 0.0) lerp(S, s, k) else s
        val tx = W / 2 - ((x0 + x1) / 2) * S
        val ty = H / 2 - ((y0 + y1) / 2) * S
        ox = if (ox != 0.0 && k < 1) lerp(ox, tx, k) else tx
        oy = if (oy != 0.0 && k < 1) lerp(oy, ty, k) else ty
    }

    /** Tiles (x, y on the floor, z up) to the screen; d is the depth (bigger is nearer). */
    private fun P(x: Double, y: Double, z: Double = 0.0): C3Pt {
        val dx = x - cx; val dy = y - cy
        val rx = dx * ca - dy * sa; val ry = dx * sa + dy * ca
        return C3Pt((ox + rx * S).toFloat(), (oy + ry * S * K - z * S * KZ).toFloat(), ry)
    }

    private fun quarter(): Int = (((Math.round((angle - PI / 4) / Q) % 4) + 4) % 4).toInt()

    fun spin(by: Int) { goal += by * Q }
    fun dragTo(a: Double) { angle = a; goal = a }
    fun release() { dragging = false; goal = Math.round((angle - PI / 4) / Q) * Q + PI / 4 }

    /** One frame of time: the spin eases, the cats move, the sparkles fade. */
    fun step(dt: Double) {
        t += dt
        if (!dragging) {
            val d = goal - angle
            if (reduce) { angle = goal; spinV = 0.0 } else {
                spinV += (d * 70 - spinV * 14) * dt
                angle += spinV * dt
                if (abs(d) < 1e-4 && abs(spinV) < 1e-3) { angle = goal; spinV = 0.0 }
            }
        }
        ca = cos(angle); sa = sin(angle)
        fit(dt)
        update(dt)
        for (f in fx) { f.life += dt; f.r += f.sp * dt }
        fx.removeAll { it.life >= it.max }
        if (tipFor != null && System.currentTimeMillis() > tipUntil) { tipFor = null; onTip(null) }
    }

    // ---------- sparkles and a floating "+25 🐟" ----------
    private fun sparkle(x: Double, y: Double, z: Double, count: Int = 10, color: Int = hx("#ffd45a")) {
        if (reduce) return
        repeat(count) { i ->
            val a = i.toDouble() / count * PI * 2 + Random.nextDouble() * 0.5
            fx.add(C3Fx(0, x, y, z, a = a, sp = 0.9 + Random.nextDouble() * 0.9, max = 0.7 + Random.nextDouble() * 0.4, size = 4 + Random.nextDouble() * 4, color = color))
        }
    }
    private fun float(x: Double, y: Double, z: Double, text: String) { fx.add(C3Fx(1, x, y, z, max = 1.6, text = text)) }

    private val textPaint = APaint(APaint.ANTI_ALIAS_FLAG).apply { textAlign = APaint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, 800, false) }
    private fun drawFx(g: ACanvas) {
        for (f in fx) {
            val p = P(f.x, f.y, f.z)
            val tt = f.life / f.max
            if (f.kind == 0) {
                val r = f.r * S * 0.9
                val x = p.x + cos(f.a) * r; val y = p.y + sin(f.a) * r * 0.7 - tt * 10 * dens
                val s = f.size * (1 - tt) * 1.4 * dens / 2
                path.reset()
                for (i in 0 until 4) {
                    val an = i * Q
                    val ax = (x + cos(an) * s).toFloat(); val ay = (y + sin(an) * s).toFloat()
                    if (i == 0) path.moveTo(ax, ay) else path.lineTo(ax, ay)
                    path.lineTo((x + cos(an + Q / 2) * s * 0.32).toFloat(), (y + sin(an + Q / 2) * s * 0.32).toFloat())
                }
                path.close()
                fill.color = f.color; fill.alpha = ((1 - tt) * 255).roundToInt().coerceIn(0, 255)
                g.drawPath(path, fill); fill.alpha = 255
            } else {
                val alpha = if (tt < 0.75) 1.0 else 1 - (tt - 0.75) / 0.25
                textPaint.textSize = clamp(S * 0.32, 14.0 * dens, 22.0 * dens).toFloat()
                val rise: Double = if (reduce) 12.0 * dens else tt * 34 * dens
                val y = (p.y - 8 * dens - rise).toFloat()
                textPaint.style = APaint.Style.STROKE; textPaint.strokeWidth = 4 * dens; textPaint.color = AColor.WHITE; textPaint.alpha = (alpha * 242).roundToInt()
                g.drawText(f.text, p.x, y, textPaint)
                textPaint.style = APaint.Style.FILL; textPaint.color = hx("#1e2627"); textPaint.alpha = (alpha * 255).roundToInt()
                g.drawText(f.text, p.x, y, textPaint)
            }
        }
    }

    private fun showTip(s: C3Spr, title: String, sub: String) { tipFor = s; tipTitle = title; tipSub = sub; tipUntil = System.currentTimeMillis() + 3200; placeTip() }
    private fun placeTip() {
        val s = tipFor ?: return
        val r = s.rect ?: return
        onTip(CafeTip(tipTitle, tipSub, clamp((r.left + r.width() / 2).toDouble(), 80.0 * dens, W - 80 * dens).toFloat(), max(8f * dens, r.top - 6 * dens)))
    }

    // ---------- drawing helpers ----------
    private fun poly(g: ACanvas, pts: List<C3Pt>, fillColor: Int?, stroke: Int? = null, lw: Float = 1f) {
        path.reset(); path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        path.close()
        if (fillColor != null) { fill.shader = null; fill.color = fillColor; g.drawPath(path, fill) }
        if (stroke != null) { line.color = stroke; line.strokeWidth = lw * dens; g.drawPath(path, line) }
    }

    /** Draw on a plane: o is a screen point, u and v are screen vectors for one tile (the web's plane()). */
    private fun plane(g: ACanvas, o: C3Pt, ux: Float, uy: Float, vx: Float, vy: Float) {
        g.save()
        mat.setValues(floatArrayOf(ux, vx, o.x, uy, vy, o.y, 0f, 0f, 1f))
        g.concat(mat)
    }

    private fun heightZ(s: C3Spr): Double { val a = CafeArt.get(ctx, s.src); return if (a != null) (s.w * a.src.height() / a.src.width()) / KZ else s.w / KZ }

    private fun sprite(g: ACanvas, s: C3Spr): RectF? {
        val a = CafeArt.get(ctx, s.src) ?: return null
        val pop = if (s.pop <= 0) 0.0 else if (s.pop >= 1) 1.0 else backOut(clamp(s.pop, 0.0, 1.0))
        if (pop <= 0.01) { s.rect = null; return null }
        val p = P(s.x, s.y, s.z + s.hop)
        var w = s.w * S * pop; var h = w * a.src.height() / a.src.width()
        if (s.cat && !reduce) { // breathing: a little taller, a little narrower
            val b = sin((t + s.phase) * PI * 2 / (if (s.nap) 5.2 else 3.6))
            h *= 1 + 0.024 * b; w *= 1 - 0.008 * b
        }
        if (s.squash > 0) { h *= 1 - s.squash; w *= 1 + s.squash * 0.6 }
        val sp = P(s.x, s.y, if (s.seat != null) s.z else 0.0)
        fill.shader = null; fill.color = if (s.cat) rgba(70, 40, 15, .18) else rgba(70, 40, 15, .14)
        g.drawOval(RectF((sp.x - w * 0.4).toFloat(), (sp.y - w * 0.4 * K).toFloat(), (sp.x + w * 0.4).toFloat(), (sp.y + w * 0.4 * K).toFloat()), fill)
        g.save()
        g.translate(p.x, p.y)
        if (s.flip) g.scale(-1f, 1f)
        if (s.lean != 0.0) g.rotate(Math.toDegrees(s.lean).toFloat())
        if (s.wiggle > 0) g.rotate(Math.toDegrees(sin(s.wiggle * 30) * 0.18 * s.wiggle).toFloat())
        bmpPaint.alpha = if (s.ghost) 217 else 255
        g.drawBitmap(a.bmp, a.src, RectF((-w / 2).toFloat(), (-h).toFloat(), (w / 2).toFloat(), 0f), bmpPaint)
        g.restore()
        val r = RectF((p.x - w / 2).toFloat(), (p.y - h).toFloat(), (p.x + w / 2).toFloat(), p.y)
        s.rect = r
        return r
    }

    // ---------- the room ----------
    fun show(d: CafeScene) {
        val newSig = listOf(d.size, d.things, d.cats.map { it.key + it.src }, d.residents.map { it.key }, d.gone, d.seed).toString()
        theme = if (THEMES.containsKey(d.theme)) d.theme else "classic"
        light = if (LIGHTS.containsKey(d.light)) d.light else "day"
        dim = d.dim
        views = d.views
        if (newSig == sig) return // the same café: the cats keep doing what they're doing
        val fresh = sig.isEmpty() || !d.quiet
        sig = newSig
        val total = d.cats.size + d.residents.size
        var i = max(0, ORDER.indexOf(d.size)) // the period's room, or a bigger one when it's crowded
        while (i < ORDER.size - 1 && total > SIZES.getValue(ORDER[i]).second) i++
        n = SIZES.getValue(ORDER[i]).first; cx = n / 2.0; cy = n / 2.0
        val r = rng(if (d.seed == 0L) 7 else d.seed)
        layout(d.things)
        cats = mutableListOf(); prints = mutableListOf()
        val seats = things.filter { it.perch != null }.sortedBy { it.pri }
        var walkers = 0; var posers = 0
        d.cats.forEachIndexed { k, c ->
            val seat = seats.getOrNull(k)
            if (seat != null) { addCat(c, seat = seat, nap = SOFT.contains(seat.id) && r() < 0.55); return@forEachIndexed }
            val spot = freeSpot(r) ?: return@forEachIndexed
            val pose = c.poses != null && posers < 3 && !reduce
            val walker = !pose && walkers < 3 && !c.kitten && !reduce && r() < 0.3
            val m = addCat(c, x = spot[0], y = spot[1], walker = walker, nap = !walker && !pose && r() < 0.3)
            if (pose) { posers++; m.poses = c.poses; m.wait = 1 + r() * 3; m.nap = false; m.src = c.src }
            if (walker) walkers++
        }
        for (c in d.residents) { val spot = freeSpot(r, "back") ?: continue; addCat(c, x = spot[0], y = spot[1]) }
        for (key in d.gone.take(6)) { // the kittens that wandered off left paw prints
            val pr = rng(cafeHash(key))
            val s = freeSpot(pr) ?: continue
            val a = pr() * PI * 2
            trail(s[0], s[1], clamp(s[0] + cos(a) * 1.3, 0.3, n - 0.3), clamp(s[1] + sin(a) * 1.3, 0.3, n - 0.3))
        }
        if (fresh && !reduce) { // pop in, back to front
            val all = (things + cats).sortedBy { it.x + it.y }
            all.forEachIndexed { k, s -> s.pop = -k * min(0.045, 1.2 / all.size) }
        }
        // start loading every painting now
        for (s in things) CafeArt.get(ctx, s.src)
        for (s in cats) { CafeArt.get(ctx, s.src); s.napSrc?.let { CafeArt.get(ctx, it) }; s.poses?.values?.forEach { CafeArt.get(ctx, it) } }
        views.values.forEach { v -> v.values.forEach { CafeArt.get(ctx, it) } }
    }

    private fun layout(ids: List<String>) {
        things = mutableListOf(); walls = mutableListOf(); rug = null
        val own = ids.toSet()
        if ("rug" in own) { val w = n * 0.42; val h = n * 0.32; rug = doubleArrayOf(n / 2.0 - w / 2 + n * 0.04, n / 2.0 - h / 2 + n * 0.06, w, h); CafeArt.get(ctx, "cafe/rug-1.webp") }
        for ((id, w) in WALL) if (id in own) { walls.add(C3WallItem(id, w.edge, w.u * n, w.v, w.w)); CafeArt.get(ctx, "cafe/$id-1.webp") }
        if ("batgarland" in own) { for (edge in listOf('N', 'W')) walls.add(C3WallItem("batgarland", edge, n / 2.0, 0.05, min(n - 0.6, 4.2))); CafeArt.get(ctx, "cafe/batgarland-1.webp") }
        walls.add(C3WallItem(null, 'E', n * 0.55, door = true))
        val sill = "snowglobe" in own && "window" in own // the snow globe sits on the window sill
        if (sill) { val win = walls.first { it.id == "window" }; walls.add(C3WallItem("snowglobe", 'N', win.u - 0.42, win.v + 0.98, 0.36)); CafeArt.get(ctx, "cafe/snowglobe-1.webp") }
        val big = if (n >= 6) 1.0 else 0.86 // a little smaller in the smallest room
        for ((id, p) in PLACE.entries.filter { (id, _) -> id in own && !(id == "snowglobe" && sill) }.sortedBy { it.value.pri }) {
            val s = C3Spr("cafe/$id-1.webp", p.w * big).apply { this.id = id; fp = p.fp?.let { it.first * big to it.second * big }; perch = p.perch; light = p.light; pri = p.pri }
            val spot = spotNear(p.u * n, p.v * n, s) ?: continue // no room for it in a room this size
            s.x = spot[0]; s.y = spot[1]
            things.add(s)
        }
    }

    private fun foot(s: C3Spr) = s.fp ?: (s.w * 0.9 to s.w * 0.9)

    private fun spotNear(x: Double, y: Double, s: C3Spr): DoubleArray? {
        val (fw, fd) = foot(s)
        val rg = rug
        fun ok(px: Double, py: Double) = px - fw / 2 >= 0.08 && px + fw / 2 <= n - 0.08 && py - fd / 2 >= 0.08 && py + fd / 2 <= n - 0.08 &&
            things.none { o -> val (ow, od) = foot(o); abs(px - o.x) < (fw + ow) / 2 + 0.05 && abs(py - o.y) < (fd + od) / 2 + 0.05 } &&
            !(rg != null && s.id != "rug" && px > rg[0] + 0.3 && px < rg[0] + rg[2] - 0.3 && py > rg[1] + 0.3 && py < rg[1] + rg[3] - 0.3 && !SOFT.contains(s.id) && s.id != "yarnbasket")
        val cxx = clamp(x, fw / 2 + 0.08, n - fw / 2 - 0.08); val cyy = clamp(y, fd / 2 + 0.08, n - fd / 2 - 0.08)
        if (ok(cxx, cyy)) return doubleArrayOf(cxx, cyy)
        var rad = 0.25
        while (rad < n * 0.6) {
            for (k in 0 until 16) {
                val a = k / 16.0 * PI * 2
                val px = cxx + cos(a) * rad; val py = cyy + sin(a) * rad
                if (ok(px, py)) return doubleArrayOf(px, py)
            }
            rad += 0.25
        }
        return null
    }

    private fun occupied(x: Double, y: Double, pad: Double = 0.55, self: C3Spr? = null): Boolean {
        for (s in things) { val (fw, fd) = foot(s); if (abs(x - s.x) < fw / 2 + pad * 0.6 && abs(y - s.y) < fd / 2 + pad * 0.6) return true }
        for (c in cats) if (c !== self && c.seat == null && hypot(c.x - x, c.y - y) < pad * 1.3) return true
        return false
    }

    private fun freeSpot(r: () -> Double, where: String? = null): DoubleArray? {
        for (k in 0 until 120) {
            val x = 0.6 + r() * (n - 1.2); val y = 0.6 + r() * (n - 1.2)
            if (where == "back" && k < 80 && x + y > n * 0.9) continue
            if (where == "front" && k < 80 && x + y < n * 1.05) continue
            if (!occupied(x, y, if (k > 90) 0.4 else 0.55)) return doubleArrayOf(x, y)
        }
        return null
    }

    private fun addCat(d: CafeCat, seat: C3Spr? = null, x: Double = 0.0, y: Double = 0.0, walker: Boolean = false, nap: Boolean = false): C3Spr {
        val asleep = nap && d.napSrc != null && !d.kitten
        val h = cafeHash(d.key)
        val c = C3Spr(if (asleep) d.napSrc!! else d.src, if (d.kitten) 0.66 else if (asleep) 0.9 else 0.8).apply {
            cat = true; key = d.key; id = d.id; title = d.title; sub = d.sub; ghost = d.ghost
            base = d.src; napSrc = d.napSrc; this.nap = asleep; kitten = d.kitten
            this.x = if (seat != null) seat.x + 0.04 else x; this.y = if (seat != null) seat.y + 0.06 else y
            this.seat = seat; this.walker = walker && seat == null
            phase = (h % 600) / 100.0; wait = 1 + (h % 50) / 10.0; flip = h % 2 == 1L
        }
        if (seat != null) c.w = min(c.w, seat.w * (if (asleep) 0.86 else 0.76))
        cats.add(c)
        return c
    }

    private fun trail(x0: Double, y0: Double, x1: Double, y1: Double) {
        val m = max(3, (hypot(x1 - x0, y1 - y0) / 0.32).roundToInt())
        val ang = kotlin.math.atan2(y1 - y0, x1 - x0)
        for (i in 0..m) prints.add(C3Print(lerp(x0, x1, i.toDouble() / m), lerp(y0, y1, i.toDouble() / m), ang, i % 2))
    }

    /** A block came home: the cat drops in near the front, bounces, and the treats float up. */
    fun arrive(d: CafeCat, text: String) {
        val r = rng(cafeHash(d.key) xor 0x5bd1L)
        val spot = freeSpot(r, "front") ?: freeSpot(r) ?: return
        val c = addCat(d, x = spot[0], y = spot[1])
        c.text = text
        CafeArt.get(ctx, c.src)
        if (reduce) landed(c) else { c.fall = true; c.z = 3.2; c.vz = 0.0 }
    }

    private fun landed(c: C3Spr) {
        sparkle(c.x, c.y, 0.4, 12)
        c.text?.let { float(c.x, c.y, heightZ(c) + 0.2, it) }
        if (c.title.isNotEmpty()) showTip(c, c.title, c.sub)
    }

    private fun pose(c: C3Spr, p: String) {
        c.posed = p
        val ps = c.poses.orEmpty()
        c.src = if (p == "sit") c.base else ps[p] ?: c.base
        c.w = if (p == "sit" || ps[p] == null) 0.8 else POSE_W[p] ?: 0.8
        c.nap = p == "loaf"
    }

    private fun mind(c: C3Spr, dt: Double) {
        val to = c.to
        if (to != null) {
            val dx = to[0] - c.x; val dy = to[1] - c.y; val d = hypot(dx, dy)
            if (d < 0.05) {
                c.to = null; c.hop = 0.0; c.lean = 0.0; c.squash = 0.08
                val pick = if (c.toWindow && c.poses?.get("back") != null) "back" else listOf("loaf", "wash", "sit", "loaf")[Random.nextInt(4)]
                pose(c, pick)
                c.wait = when (pick) { "back" -> 9 + Random.nextDouble() * 5; "loaf" -> 7 + Random.nextDouble() * 6; else -> 4 + Random.nextDouble() * 3 }
                return
            }
            if (c.posed != "walk") pose(c, "walk")
            val sp = min(d, 0.6 * dt)
            c.x += dx / d * sp; c.y += dy / d * sp; c.step += dt
            val ph = sin(c.step * 11)
            c.hop = abs(ph) * 0.04; c.lean = ph * 0.02
            val sx = P(to[0], to[1]).x - P(c.x, c.y).x
            if (abs(sx) > 1) c.flip = sx < 0 // the walking paintings face right
            return
        }
        c.wait -= dt
        if (c.wait > 0) return
        val posed = c.posed
        if (posed != null && posed != "stretch" && posed != "sit" && c.poses?.get("stretch") != null) { pose(c, "stretch"); c.wait = 1.6; return }
        val win = walls.firstOrNull { it.id == "window" }
        var dest: DoubleArray? = null
        c.toWindow = false
        if (win != null && c.poses?.get("back") != null && Random.nextDouble() < 0.35) {
            val spot = doubleArrayOf(clamp(win.u, 0.8, n - 0.8), 0.7)
            if (!occupied(spot[0], spot[1], 0.45, c)) { dest = spot; c.toWindow = true }
        }
        var k = 0
        while (dest == null && k < 14) {
            val x = clamp(c.x + (Random.nextDouble() - 0.5) * 4, 0.6, n - 0.6); val y = clamp(c.y + (Random.nextDouble() - 0.5) * 4, 0.6, n - 0.6)
            if (!occupied(x, y, 0.5, c) && hypot(x - c.x, y - c.y) > 1) dest = doubleArrayOf(x, y)
            k++
        }
        if (dest != null) c.to = dest else c.wait = 2.0
    }

    private fun walk(c: C3Spr, dt: Double) { // a cat without walking pictures hops along, leaning into it
        val to = c.to
        if (to != null) {
            val dx = to[0] - c.x; val dy = to[1] - c.y; val d = hypot(dx, dy)
            if (d < 0.05) { c.to = null; c.wait = 3 + Random.nextDouble() * 7; c.hop = 0.0; c.lean = 0.0; c.squash = 0.1; return }
            val sp = min(d, 0.55 * dt)
            c.x += dx / d * sp; c.y += dy / d * sp; c.step += dt
            val ph = sin(c.step * 9)
            c.hop = abs(ph) * 0.09; c.lean = 0.07 + ph * 0.035
            if (abs(ph) < 0.12) c.squash = 0.05
            val sx = P(to[0], to[1]).x - P(c.x, c.y).x
            if (abs(sx) > 2) c.flip = sx < 0
        } else {
            c.wait -= dt
            if (c.wait <= 0) {
                for (k in 0 until 12) {
                    val x = clamp(c.x + (Random.nextDouble() - 0.5) * 3, 0.6, n - 0.6); val y = clamp(c.y + (Random.nextDouble() - 0.5) * 3, 0.6, n - 0.6)
                    if (!occupied(x, y, 0.5, c)) { c.to = doubleArrayOf(x, y); break }
                }
                if (c.to == null) c.wait = 2.0
            }
        }
    }

    private fun update(dt: Double) {
        for (s in things) { if (s.pop < 1) s.pop = min(1.0, s.pop + dt * 3.2); if (s.wiggle > 0) s.wiggle = max(0.0, s.wiggle - dt) }
        for (c in cats) {
            if (c.pop < 1) c.pop = min(1.0, c.pop + dt * 3.2)
            c.seat?.let { st -> c.z = heightZ(st) * (st.perch ?: 0.0) * (if (st.pop >= 1) 1.0 else backOut(clamp(st.pop, 0.0, 1.0))) }
            if (c.fall) {
                c.vz -= 13 * dt; c.z += c.vz * dt
                if (c.z <= 0) {
                    c.z = 0.0
                    if (c.vz < -2.5) { c.vz *= -0.28; c.squash = 0.22; if (!c.didLand) { c.didLand = true; landed(c) } } else { c.fall = false; c.vz = 0.0 }
                }
            }
            if (c.squash > 0) c.squash = max(0.0, c.squash - dt * 1.4)
            if (c.hopT >= 0) {
                c.hopT += dt
                val T = 0.38
                c.hop = sin(PI * clamp(c.hopT / T, 0.0, 1.0)) * 0.45
                if (c.hopT >= T) { c.hop = 0.0; c.hopT = -1.0; c.squash = 0.14 }
            }
            if (c.fall || reduce) continue
            if (c.poses != null) { mind(c, dt); continue }
            if (c.walker) walk(c, dt)
        }
    }

    private fun thingSrc(s: C3Spr, q: Int): Pair<String, Boolean> {
        val vw = views[s.id]
        if (vw == null || q == 0) return s.src to false
        val back = vw["back"]; val side = vw["side"]
        if (q == 2 && back != null) return back to false
        if (q == 2 && side != null) return side to true
        if ((q == 1 || q == 3) && side != null) return side to (q == 3)
        return s.src to (q == 3)
    }

    fun draw(g: ACanvas) {
        if (W <= 0 || S <= 0) return
        val T = THEMES.getValue(theme)
        val R = rim; val D = depth; val Hh = wallH
        val Lt = LIGHTS.getValue(light)
        val tinted = Lt.tint != null || dim
        if (tinted) g.saveLayer(null, null)
        // the shadow under the floating café
        val c0 = P(n / 2.0, n / 2.0, -D - 0.2)
        val shR = (n * 0.78 * S).toFloat()
        fill.shader = RadialGradient(c0.x, c0.y, max(1f, shR), rgba(40, 30, 20, .22), rgba(40, 30, 20, 0.0), Shader.TileMode.CLAMP)
        g.save(); g.translate(c0.x, c0.y); g.scale(1f, 0.42f); g.translate(-c0.x, -c0.y)
        g.drawCircle(c0.x, c0.y, shR, fill); g.restore(); fill.shader = null
        // the base: the sides facing us, with bricks, then the rim on top
        val corners = listOf(doubleArrayOf(-R, -R), doubleArrayOf(n + R, -R), doubleArrayOf(n + R, n + R), doubleArrayOf(-R, n + R))
        for (i in 0 until 4) {
            val a = corners[i]; val b = corners[(i + 1) % 4]
            val m = P((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            if (m.d <= 0) continue
            val pts = listOf(P(a[0], a[1], 0.0), P(b[0], b[1], 0.0), P(b[0], b[1], -D), P(a[0], a[1], -D))
            val facing = abs(if (a[0] == b[0]) ca else sa)
            poly(g, pts, shade(T.slab, -0.12 * (1 - facing)))
            g.save()
            path.reset(); path.moveTo(pts[0].x, pts[0].y); for (p in pts.drop(1)) path.lineTo(p.x, p.y); path.close(); g.clipPath(path)
            line.color = rgba(60, 30, 10, .16); line.strokeWidth = dens
            val L = n + 2 * R
            for (row in 1 until 3) { val z = -D * row / 3; val p1 = P(a[0], a[1], z); val p2 = P(b[0], b[1], z); g.drawLine(p1.x, p1.y, p2.x, p2.y, line) }
            for (row in 0 until 3) {
                var k = 0
                while (k <= L * 1.6) {
                    val tt = (k + (row % 2) * 0.5) / (L * 1.6)
                    if (tt > 0 && tt < 1) {
                        val x = lerp(a[0], b[0], tt); val y = lerp(a[1], b[1], tt)
                        val p1 = P(x, y, -D * row / 3); val p2 = P(x, y, -D * (row + 1) / 3)
                        g.drawLine(p1.x, p1.y, p2.x, p2.y, line)
                    }
                    k++
                }
            }
            g.restore()
        }
        poly(g, corners.map { P(it[0], it[1], 0.0) }, T.trim)
        // the floor, in boards
        for (j in 0 until n) for (i in 0 until n) poly(g, listOf(P(i.toDouble(), j.toDouble()), P(i + 1.0, j.toDouble()), P(i + 1.0, j + 1.0), P(i.toDouble(), j + 1.0)), if ((i + j) % 2 == 1) T.floor.first else T.floor.second)
        line.color = T.board; line.strokeWidth = dens
        for (k in 0..n * 2) { val p1 = P(0.0, k / 2.0); val p2 = P(n.toDouble(), k / 2.0); g.drawLine(p1.x, p1.y, p2.x, p2.y, line) }
        // the rug, laid flat
        rug?.let { (rx, ry, rw, rh) ->
            val a = CafeArt.get(ctx, "cafe/rug-1.webp") ?: return@let
            val o = P(rx, ry); val u = P(rx + 1, ry); val w = P(rx, ry + 1)
            plane(g, o, u.x - o.x, u.y - o.y, w.x - o.x, w.y - o.y)
            bmpPaint.alpha = 242
            g.drawBitmap(a.bmp, a.src, RectF(0f, 0f, rw.toFloat(), rh.toFloat()), bmpPaint)
            bmpPaint.alpha = 255
            g.restore()
        }
        // paw prints
        fill.color = rgba(110, 70, 40, .4)
        for (p in prints) {
            val o = P(p.x, p.y); val u = P(p.x + 1, p.y); val w = P(p.x, p.y + 1)
            plane(g, o, u.x - o.x, u.y - o.y, w.x - o.x, w.y - o.y)
            g.rotate(Math.toDegrees(p.a).toFloat()); g.translate(0f, if (p.side == 1) 0.07f else -0.07f)
            g.drawOval(RectF(-0.06f, -0.05f, 0.06f, 0.05f), fill)
            for ((tx, ty) in listOf(0.07f to -0.045f, 0.09f to 0f, 0.07f to 0.045f)) g.drawCircle(tx, ty, 0.022f, fill)
            g.restore()
        }
        // the two walls at the back (the front two are cut away, like a dollhouse)
        class W(val edge: Char, val a: DoubleArray, val b: DoubleArray, val d: Double)
        val ws = mutableListOf<W>()
        for ((edge, ab) in EDGES) {
            val a = doubleArrayOf(ab.first[0] * n, ab.first[1] * n); val b = doubleArrayOf(ab.second[0] * n, ab.second[1] * n)
            val m = P((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            if (m.d < -0.01 * n) ws.add(W(edge, a, b, m.d))
        }
        ws.sortBy { it.d }
        for (w in ws) drawWall(g, w.edge, w.a, w.b, T, Hh)
        for (w in ws) {
            val out = when (w.edge) { 'N' -> doubleArrayOf(0.0, -R); 'S' -> doubleArrayOf(0.0, R); 'W' -> doubleArrayOf(-R, 0.0); else -> doubleArrayOf(R, 0.0) }
            val A = w.a; val B = w.b
            poly(g, listOf(P(A[0], A[1], Hh), P(B[0], B[1], Hh), P(B[0] + out[0], B[1] + out[1], Hh), P(A[0] + out[0], A[1] + out[1], Hh)), T.trim, rgba(90, 60, 30, .18))
            for (E in listOf(A, B)) {
                if (P(E[0], E[1]).d < -0.4 * n) continue // the corner at the very back is hidden
                poly(g, listOf(P(E[0], E[1], 0.0), P(E[0] + out[0], E[1] + out[1], 0.0), P(E[0] + out[0], E[1] + out[1], Hh), P(E[0], E[1], Hh)), shade(T.trim, -0.08))
            }
        }
        // everything standing, back to front
        val q = quarter()
        val list = (things + cats)
        for (s in list) s.d = P(s.x, s.y).d + (if (s.seat != null) 0.08 else 0.0) + (if (s.cat) 0.01 else 0.0)
        for (s in list.sortedBy { it.d }) {
            if (s.cat) { sprite(g, s); continue }
            val (src, flip) = thingSrc(s, q)
            val keep = s.src
            s.src = if (CafeArt.isBad(src)) keep else src; s.flip = flip
            if (CafeArt.get(ctx, s.src) == null && s.src != keep) s.src = keep // its turned picture isn't ready yet
            sprite(g, s)
            s.src = keep
        }
        // low walls along the front edges, so the room has a lip
        val lip = 0.2
        for ((edge, ab) in EDGES) {
            val a = doubleArrayOf(ab.first[0] * n, ab.first[1] * n); val b = doubleArrayOf(ab.second[0] * n, ab.second[1] * n)
            val m = P((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            if (m.d < -0.01 * n) continue
            val out = when (edge) { 'N' -> doubleArrayOf(0.0, -R); 'S' -> doubleArrayOf(0.0, R); 'W' -> doubleArrayOf(-R, 0.0); else -> doubleArrayOf(R, 0.0) }
            val fa = doubleArrayOf(a[0] + out[0], a[1] + out[1]); val fb = doubleArrayOf(b[0] + out[0], b[1] + out[1])
            poly(g, listOf(P(fa[0], fa[1], 0.0), P(fb[0], fb[1], 0.0), P(fb[0], fb[1], lip), P(fa[0], fa[1], lip)), shade(T.skirt, -0.06))
            poly(g, listOf(P(a[0], a[1], lip), P(b[0], b[1], lip), P(fb[0], fb[1], lip), P(fa[0], fa[1], lip)), T.trim, rgba(90, 60, 30, .15))
        }
        // the light: a tint over the whole room, and at evening and night a warm glow around the lamps
        if (tinted) {
            fill.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            val tint = Lt.tint ?: rgba(10, 12, 30, .28)
            fill.shader = null; fill.color = tint; g.drawRect(0f, 0f, W.toFloat(), H.toFloat(), fill)
            if (dim && Lt.tint != null) { fill.color = rgba(10, 12, 30, .14); g.drawRect(0f, 0f, W.toFloat(), H.toFloat(), fill) }
            val glow = Lt.glow
            if (glow != null) for (s in things) {
                val lh = s.light ?: continue
                if (s.pop < 1) continue
                val p = P(s.x, s.y, heightZ(s) * lh)
                val r = (Lt.r * S * (if (s.id == "lamp") 1.0 else 0.55)).toFloat()
                fill.shader = RadialGradient(p.x, p.y, max(1f, r), glow, rgba(255, 190, 105, 0.0), Shader.TileMode.CLAMP)
                g.drawRect(p.x - r, p.y - r, p.x + r, p.y + r, fill)
            }
            fill.shader = null; fill.xfermode = null
            g.restore()
        }
        drawFx(g)
        placeTip()
    }

    private fun drawWall(g: ACanvas, edge: Char, a0: DoubleArray, b0: DoubleArray, T: C3Theme, Hh: Double) {
        var A = a0; var B = b0; var flip = false
        if (P(B[0], B[1]).x < P(A[0], A[1]).x) { A = b0; B = a0; flip = true }
        val L = n.toDouble()
        val o = P(A[0], A[1], Hh); val e = P(B[0], B[1], Hh)
        plane(g, o, ((e.x - o.x) / L).toFloat(), ((e.y - o.y) / L).toFloat(), 0f, (S * KZ).toFloat())
        fill.shader = LinearGradient(0f, 0f, 0f, Hh.toFloat(), T.wall.first, T.wall.second, Shader.TileMode.CLAMP)
        g.drawRect(0f, 0f, L.toFloat(), Hh.toFloat(), fill); fill.shader = null
        val lit = if (edge == 'N' || edge == 'S') abs(sa) else abs(ca) // the light falls differently on each wall
        fill.color = rgba(80, 50, 20, 0.1 * (1 - lit)); g.drawRect(0f, 0f, L.toFloat(), Hh.toFloat(), fill)
        fill.color = T.dots
        var row = 0; var yy = 0.22
        while (yy < Hh - 0.4) { var xx = 0.15 + (row % 2) * 0.15; while (xx < L) { g.drawCircle(xx.toFloat(), yy.toFloat(), 0.022f, fill); xx += 0.3 }; row++; yy += 0.3 }
        fill.color = T.skirt; g.drawRect(0f, (Hh - 0.22).toFloat(), L.toFloat(), Hh.toFloat(), fill)
        fill.color = T.skirtLine; g.drawRect(0f, (Hh - 0.24).toFloat(), L.toFloat(), (Hh - 0.2).toFloat(), fill)
        for (it in walls.filter { it.edge == edge }) {
            val uu = if (flip) L - it.u else it.u
            if (it.door) { drawDoor(g, uu, Hh); continue }
            val a = CafeArt.get(ctx, "cafe/${it.id}-1.webp") ?: continue
            g.drawBitmap(a.bmp, a.src, RectF((uu - it.w / 2).toFloat(), it.v.toFloat(), (uu + it.w / 2).toFloat(), (it.v + it.w * a.src.height() / a.src.width()).toFloat()), bmpPaint)
        }
        g.restore()
    }

    private fun drawDoor(g: ACanvas, u: Double, Hh: Double) {
        val w = 0.95f; val h = 1.75f; val x = (u - w / 2).toFloat(); val y = (Hh - h - 0.04).toFloat()
        fun rr(x1: Float, y1: Float, w1: Float, h1: Float, top: Float, bottom: Float, color: Int) {
            path.reset()
            path.addRoundRect(RectF(x1, y1, x1 + w1, y1 + h1), floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom), APath.Direction.CW)
            fill.color = color; g.drawPath(path, fill)
        }
        rr(x - 0.06f, y - 0.06f, w + 0.12f, h + 0.08f, 0.5f, 0f, hx("#b98252"))
        rr(x, y, w, h, 0.45f, 0f, hx("#d9a46c"))
        rr(x + 0.18f, y + 0.18f, w - 0.36f, 0.55f, 0.3f, 0.05f, hx("#a7d7ee"))
        fill.color = hx("#7a4c2a"); g.drawCircle(x + w - 0.16f, y + h * 0.58f, 0.045f, fill)
        rr(x + 0.16f, y + 0.86f, w - 0.32f, 0.26f, 0.05f, 0.05f, hx("#fff8ec"))
        line.color = hx("#7a4c2a"); line.strokeWidth = 0.025f; g.drawRect(x + 0.16f, y + 0.86f, x + w - 0.16f, y + 1.12f, line)
        g.save(); g.translate(u.toFloat(), y + 1.0f); g.scale(0.01f, 0.01f)
        textPaint.style = APaint.Style.FILL; textPaint.color = hx("#2c8a7e"); textPaint.alpha = 255; textPaint.textSize = 17f
        g.drawText("OPEN", 0f, 7f, textPaint)
        g.restore()
    }

    /** A finger on (x, y): the frontmost cat or thing there hops or wiggles, and says what it is. */
    fun tap(x: Float, y: Float) {
        val list = (things + cats).filter { it.rect != null }.sortedByDescending { it.d }
        for (s in list) {
            val r = s.rect!!
            if (!r.contains(x, y)) continue
            if (s.cat) {
                if (s.nap) s.squash = 0.16 else if (s.hopT < 0 && !s.fall) s.hopT = 0.0
                if (s.title.isNotEmpty()) showTip(s, s.title, s.sub)
            } else {
                s.wiggle = 0.35
                names[s.id]?.let { showTip(s, it.first, it.second) }
            }
            return
        }
        tipFor = null; onTip(null)
    }
}

private fun reduceMotion(ctx: Context): Boolean =
    runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

/** A cat arriving: shown after `delayMs`, once per key. */
data class CafeArrival(val cat: CafeCat, val text: String, val delayMs: Long = 450)

/**
 * The 3D café in a box. Drag sideways to turn it (it settles on a quarter turn), tap a cat or a thing.
 * `arrival` drops a cat in (the homecoming).
 */
@Composable
fun CafeView(scene: CafeScene, names: Map<String, Pair<String, String>>, modifier: Modifier = Modifier, arrival: CafeArrival? = null, margin: Float = 12f) {
    val ctx = LocalContext.current
    val dens = LocalDensity.current.density
    val engine = remember { Cafe3D(ctx, dens, reduceMotion(ctx)) }
    var tip by remember { mutableStateOf<CafeTip?>(null) }
    engine.names = names
    engine.margin = margin.toDouble()
    engine.onTip = { tip = it }
    remember(scene) { engine.show(scene); true }
    LaunchedEffect(arrival?.cat?.key) {
        val a = arrival ?: return@LaunchedEffect
        delay(a.delayMs)
        engine.arrive(a.cat, a.text)
    }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(engine) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(0.05)
                last = now
                engine.step(dt)
                frame = now
            }
        }
    }
    Box(modifier.semantics { contentDescription = "Your Cat Café. Drag to turn it, tap a cat." }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(engine) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val x0 = down.position.x; val y0 = down.position.y; val a0 = engine.angle
                    var moved = false; var gaveUp = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (moved) engine.release() else if (!gaveUp) engine.tap(ch.position.x, ch.position.y)
                            break
                        }
                        val dx = ch.position.x - x0; val dy = ch.position.y - y0
                        if (!moved && !gaveUp) {
                            if (abs(dy) > 10 * dens && abs(dy) > abs(dx)) gaveUp = true // a scroll, not a turn
                            else if (abs(dx) > 6 * dens) { moved = true; engine.dragging = true }
                        }
                        if (moved) { engine.dragTo(a0 + dx / dens * 0.012); ch.consume() }
                    }
                    if (moved) engine.release()
                }
            },
        ) {
            frame // redraw every frame
            engine.resize(size.width, size.height)
            drawIntoCanvas { engine.draw(it.nativeCanvas) }
        }
        tip?.let { t ->
            val wPx = with(LocalDensity.current) { 180.dp.toPx() }
            Column(
                Modifier.offset { IntOffset((t.x - wPx / 2).roundToInt(), (t.y - 56 * dens).roundToInt().coerceAtLeast(0)) }
                    .widthIn(max = 180.dp).shadow(4.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(t.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (t.sub.isNotEmpty()) Text(t.sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}


// ---------- what goes in the café (the web's cafeData / cafeCat) ----------

/** A block that came home, as a café cat: under 25 minutes a kitten, otherwise grown, with its poses. */
fun cafeCat(s: xyz.tiresia.kavana.UiState, b: xyz.tiresia.kavana.Hist): CafeCat {
    val c = s.cat(b.cat)
    val small = b.actual < 25
    return CafeCat(
        key = b.id, id = c.id, src = if (small) xyz.tiresia.kavana.Art.kitten(c.id) else xyz.tiresia.kavana.Art.portrait(c.id),
        napSrc = if (small) null else xyz.tiresia.kavana.Art.nap(c.id), kitten = small, poses = if (small) null else c.poses,
        ghost = c.ghost, title = c.name, sub = "${xyz.tiresia.kavana.titleOf(b.intention, b.tag)} · ${xyz.tiresia.kavana.fmtMin(b.actual)}",
    )
}

/** Everything the café draws for a list of blocks: your things, the cats that came home (the first 60), the
 *  shelter cats who live there, and paw prints for the ones that wandered off. */
fun cafeScene(s: xyz.tiresia.kavana.UiState, list: List<xyz.tiresia.kavana.Hist>, size: String, seed: Long, dark: Boolean, quiet: Boolean = true): CafeScene {
    val home = list.filter { it.kitten == "home" }.sortedBy { it.start }
    return CafeScene(
        size = size, seed = seed, theme = s.view.settings.theme, light = cafeLightAt(), dim = dark,
        things = s.shop.items.filter { s.owns(it.id) }.map { it.id },
        views = s.shop.items.mapNotNull { t -> t.views?.let { t.id to it } }.toMap(),
        cats = if (s.catsOn) home.take(60).map { cafeCat(s, it) } else emptyList(),
        residents = s.shop.shelter.filter { s.owns(it.id) }.map { CafeCat(key = it.id, id = it.id, src = xyz.tiresia.kavana.Art.portrait(it.id), title = it.name, sub = "A shelter cat you sponsored") },
        gone = list.filter { it.kitten == "gone" }.map { it.id }, quiet = quiet,
    )
}

/** The names and lines of the café's things, for the label when you tap one. */
fun cafeNames(s: xyz.tiresia.kavana.UiState): Map<String, Pair<String, String>> = s.shop.items.associate { it.id to (it.name to it.about) }
