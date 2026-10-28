package xyz.tiresia.kavana.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import xyz.tiresia.kavana.ui.drawWall
import xyz.tiresia.kavana.ui.wallFor
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The pictures of the playful widgets, drawn into bitmaps like the others: the cat on its sill (sitting, mid-hop
 * with a heart, purring in its loaf with rings and hearts, asleep with its z's), in the square, the wide and
 * the slim-strip shapes, and the clock's face. The numbers are the board's, in units of its 168-wide square.
 */
object PlayArt {
    enum class Pose { SIT, HOP, PURR, SLEEP, DREAM }

    private val HEART: Path = PathParser().parsePathString("M0 0c-5-7-15-3-12 5 2 5 12 11 12 11s10-6 12-11c3-8-7-12-12-5z").toPath()
    private val HOP_LINES: Path = PathParser().parsePathString("M30 70l-10 6M26 88l-12 2M34 56l-8-8").toPath()

    private val RED = Color(0xFFE0566B)
    private val PINK = Color(0xFFF29BB0)
    private val TEAL = Color(0xFF2C8A7E)
    private val TWIG = Color(0xFFC99F6D)

    private fun DrawScope.heart(x: Float, y: Float, k: Float, color: Color, alpha: Float = 1f) =
        withTransform({ translate(x, y); scale(k, k, Offset.Zero) }) { drawPath(HEART, color, alpha) }

    /** Draws a cat painting `size` wide with its bottom edge on `bottom`, centred on `cx`. */
    private fun DrawScope.catAt(img: ImageBitmap, cx: Float, bottom: Float, size: Float, tint: Color? = null, alpha: Float = 1f) {
        val si = size.toInt()
        drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset((cx - size / 2).toInt(), (bottom - size).toInt()), dstSize = IntSize(si, si),
            alpha = alpha, filterQuality = FilterQuality.High, colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.SrcIn) })
    }

    /** The glow Boo has: soft copies of the painting in lavender, all round it. */
    private fun DrawScope.glow(img: ImageBitmap, cx: Float, bottom: Float, size: Float, u: Float) {
        for (i in 0 until 12) {
            val a = i * PI.toFloat() / 6f
            catAt(img, cx + 5f * u * cos(a), bottom + 5f * u * sin(a), size, Color(0xFFA99BE8), .07f)
        }
    }

    private fun DrawScope.zs(rightX: Float, topY: Float, u: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCFC6FF.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        paint.textSize = 22f * u; drawContext.canvas.nativeCanvas.drawText("z", rightX, topY + 22f * u, paint)
        paint.textSize = 15f * u; paint.alpha = 178; drawContext.canvas.nativeCanvas.drawText("z", rightX - 18f * u, topY + 40f * u, paint)
    }

    private fun DrawScope.rings(c: Offset, u: Float) {
        for ((r, a) in listOf(54f to .5f, 72f to .3f, 90f to .16f)) drawCircle(TEAL.copy(alpha = a), r * u, c, style = Stroke(2f * u))
    }

    /** The bars badge in a corner while the cat purrs. */
    private fun DrawScope.purrBadge(right: Float, top: Float, u: Float) {
        val c = Offset(right - 14f * u, top + 14f * u)
        drawCircle(Color(43, 33, 26, 20), 14f * u, c)
        for ((x, y0, y1) in listOf(Triple(-5f, -6f, 6f), Triple(-9f, -3.5f, 3.5f), Triple(0f, -9f, 9f), Triple(5f, -6f, 6f), Triple(9f, -3.5f, 3.5f)))
            drawLine(TEAL, Offset(c.x + x * u * .75f, c.y + y0 * u * .75f), Offset(c.x + x * u * .75f, c.y + y1 * u * .75f), 1.6f * u, StrokeCap.Round)
    }

    /**
     * The whole widget as a picture: the café wall (the night wall after ten, whatever the theme), the sill, and the
     * cat; the text and the buttons are drawn over it by the widget. Square, wide (4 x 2) or a slim strip, by its size.
     */
    fun pet(ctx: Context, s: Snap, wDp: Float, hDp: Float, pose: Pose, dark: Boolean, boo: Boolean, night: Boolean): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val theme = if (night) "night" else s.theme
        val wall = wallFor(theme, dark || night)
        val strip = hDp < 100f
        val wide = !strip && wDp >= 250f
        val sleeping = pose == Pose.SLEEP || pose == Pose.DREAM
        val catPath = when {
            sleeping -> if (boo) s.booNap else s.nap
            pose == Pose.PURR -> s.loaf ?: s.nap
            else -> s.portrait
        }
        val sill = when {
            night -> Color(0xFF5A4E63) to Color(0xFF3F3548)
            theme == "library" -> Color(0xFFB98D5C) to Color(0xFF8F6A40)
            else -> Color(0xFFE2C196) to Color(0xFFC99F6D)
        }
        val img = if (s.catsOn) WidgetArt.cat(ctx, catPath) else null
        return WidgetArt.render((wDp * d).toInt(), (hDp * d).toInt(), d) {
            val w = size.width
            val h = size.height
            val u = when { strip -> h / 84f; wide -> h / 176f; else -> min(w, h) / 168f }
            val r = if (strip) min(30f * u, h / 2.2f) else 30f * u
            clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r))) }) { drawWall(wall) }
            if (wall.lightInk) drawRoundRect(Color.White.copy(alpha = .07f), Offset(density / 2, density / 2), Size(w - density, h - density), CornerRadius(r - density / 2), style = Stroke(density))
            val hop = if (pose == Pose.HOP) 22f * u else 0f
            if (strip) {
                val sz = 92f * u
                if (img != null) {
                    if (sleeping) {
                        val cx = 22f * u + 36f * u
                        if (boo) glow(img, cx, h - 4f * u, 72f * u, u)
                        catAt(img, cx, h - 4f * u, 72f * u)
                        zs(114f * u, 6f * u, u * .62f)
                    } else {
                        clipRect(22f * u, 22f * u - 10f * u, 22f * u + sz, h) { catAt(img, 22f * u + sz / 2, 22f * u + sz - (hop * .45f), sz) }
                        if (pose == Pose.HOP) heart(124f * u, 16f * u, .6f * u, RED)
                        if (pose == Pose.PURR) { heart(124f * u, 20f * u, .6f * u, RED); heart(138f * u, 12f * u, .4f * u, PINK) }
                    }
                }
                return@render
            }
            // the sill
            val sillRight = if (wide) 14f * u + w * .5f else w - 14f * u
            val sillTop = h - 32f * u
            drawRoundRect(Brush.verticalGradient(listOf(sill.first, sill.second), startY = sillTop, endY = h - 14f * u), Offset(14f * u, sillTop), Size(sillRight - 14f * u, 18f * u), CornerRadius(9f * u))
            val cs = if (wide) 128f * u else 124f * u
            val cx = (if (wide) 36f * u else 22f * u) + cs / 2
            val bottom = h - 22f * u - hop
            val mid = if (wide) Offset(cx, h - 22f * u - cs / 2) else Offset(84f * u, 108f * u)
            if (pose == Pose.PURR || pose == Pose.DREAM) rings(mid, u)
            if (pose == Pose.HOP) drawOval(Color(80, 50, 20, 56), Offset(cx - 35f * u, h - 34f * u), Size(70f * u, 10f * u))
            if (img != null) {
                if (boo && sleeping) glow(img, cx, bottom, cs, u)
                catAt(img, cx, bottom, cs)
            }
            when (pose) {
                Pose.HOP -> {
                    val k = if (wide) Offset(cx + cs * .42f, h - 22f * u - cs * .95f) else Offset(132f * u, 40f * u)
                    heart(k.x, k.y, u, RED)
                    withTransform({ translate(if (wide) 14f * u else 0f, if (wide) 18f * u else 0f); scale(u, u, Offset.Zero) }) { drawPath(HOP_LINES, TWIG, style = Stroke(3f, cap = StrokeCap.Round)) }
                }
                Pose.PURR, Pose.DREAM -> {
                    if (wide) {
                        heart(mid.x + cs * .42f, mid.y - cs * .42f, .83f * u, RED)
                        heart(mid.x - cs * .35f, mid.y - cs * .55f, .58f * u, PINK)
                        heart(mid.x + cs * .08f, mid.y - cs * .7f, .42f * u, RED, .8f)
                    } else {
                        heart(126f * u, 52f * u, .83f * u, RED)
                        heart(44f * u, 36f * u, .58f * u, PINK)
                        heart(96f * u, 20f * u, .42f * u, RED, .8f)
                        purrBadge(w - 12f * u, 12f * u, u)
                    }
                }
                else -> {}
            }
            if (sleeping) zs(w - (if (wide) w * .5f else 22f * u), 18f * u, u)
        }
    }


    // ---------- the sky: the month's constellation, the week as weather ----------

    private fun midnightOf(t: Long): Long = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    /** A stable "random" number 0..1 for a time, so a star is always in the same place. */
    private fun place(t: Long): Float = (((t / 1000) * 2654435761L) and 0xffffL) / 65535f

    private class Pt(val x: Float, val y: Float, val r: Float, val day: Long, val min: Int)

    /**
     * The month as a night sky: a star for every block that came home, left to right by the day (a longer block is a bigger
     * star), the days of your streak in amber with a line through them, the brightest star with a halo. Or, small, the
     * last two weeks: a star for every day that had one.
     */
    fun constellation(ctx: Context, s: Snap, wDp: Float, hDp: Float, fortnight: Boolean, now: Long): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val dim = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return WidgetArt.render((wDp * d).toInt(), (hDp * d).toInt(), d) {
            val w = size.width
            val h = size.height
            val u = if (fortnight) min(w, h) / 168f else h / 176f
            clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(30f * u))) }) {
                drawRect(Brush.verticalGradient(0f to Color(0xFF141A3E), .7f to Color(0xFF2B2E5E), 1f to Color(0xFF3A396B)))
                drawRect(Brush.radialGradient(listOf(Color(120, 110, 200, 102), Color.Transparent), center = Offset(w * .8f, h * .1f), radius = w * .5f))
            }
            val rnd = java.util.Random(11)
            repeat(14) { i -> drawCircle(Color.White.copy(alpha = .5f), (.7f + rnd.nextFloat() * .2f) * u, Offset(rnd.nextFloat() * w, if (i % 2 == 0) h * (.1f + rnd.nextFloat() * .16f) else h * (.84f + rnd.nextFloat() * .12f))) }
            val x0 = w * .07f; val x1 = w * .95f; val y0 = h * .36f; val y1 = h * .78f
            fun radius(min: Int) = (2f + 2.2f * min(min, 90) / 90f) * u
            val pts = if (!fortnight) s.stars.map { st ->
                val c = Calendar.getInstance().apply { timeInMillis = st.t }
                val at = (c.get(Calendar.DAY_OF_MONTH) - 1 + c.get(Calendar.HOUR_OF_DAY) / 24f) / dim
                Pt(x0 + at * (x1 - x0), y0 + place(st.t) * (y1 - y0), radius(st.min), midnightOf(st.t), st.min)
            } else s.days.mapIndexedNotNull { i, day -> if (day.min <= 0) null else Pt(x0 + i / 13f * (x1 - x0), y0 + place(day.day) * (y1 - y0), radius(day.min), day.day, day.min) }
            // the streak: the days in a row, up to today (or yesterday), each with its biggest star
            val byDay = pts.groupBy { it.day }
            val last = byDay.keys.maxOrNull()
            val streak = ArrayList<Pt>()
            if (last != null && s.streak > 0) {
                var k: Long = last
                while (byDay.containsKey(k) && streak.size < s.streak) {
                    streak.add(0, byDay.getValue(k).maxByOrNull { it.min }!!)
                    k = Calendar.getInstance().apply { timeInMillis = k; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
                }
            }
            val amber = Color(0xFFFFCF8A)
            val bright = pts.maxByOrNull { it.min }
            if (bright != null && streak.none { it === bright }) drawCircle(Color(0xFFCFD8FF).copy(alpha = .16f), 8f * u, Offset(bright.x, bright.y))
            for (p in pts) if (streak.none { it === p }) drawCircle(Color(0xFFCFD8FF), p.r, Offset(p.x, p.y))
            if (streak.size > 1) drawPath(Path().apply { streak.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }, amber.copy(alpha = .9f), style = Stroke(1.4f * u, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            for ((i, p) in streak.withIndex()) {
                if (i == streak.lastIndex) { drawCircle(amber.copy(alpha = .25f), 9f * u, Offset(p.x, p.y)); drawCircle(Color(0xFFFFF3D6), 4.4f * u, Offset(p.x, p.y)) }
                else drawCircle(amber, p.r + .6f * u, Offset(p.x, p.y))
            }
        }
    }

    /** The sky for the hour a block started: dawn, day, sunset or night. */
    private fun skyFor(hour: Int): Pair<Color, Color> = when (hour) {
        in 5..9 -> Color(0xFF8A64AD) to Color(0xFFEE9D86)
        in 10..15 -> Color(0xFF8FCDF0) to Color(0xFFE7F6FF)
        in 16..20 -> Color(0xFFF2A17C) to Color(0xFFFBD0A0)
        else -> Color(0xFF1F2756) to Color(0xFF4A4F8C)
    }

    /**
     * Seven little skies in a row, the last seven days: the sky is the hour the day's longest block started, the cat is the one
     * that came home, a day with no block is a dim sky with a sleeping cat, and today has a dashed edge.
     */
    fun week(ctx: Context, s: Snap, wDp: Float, hDp: Float, now: Long, tealEdge: Color): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val days = s.days.takeLast(7)
        val imgs = days.map { day -> (if (day.art.isNotEmpty()) day.art else s.portrait).let { WidgetArt.cat(ctx, it) } }
        val nap = WidgetArt.cat(ctx, s.nap)
        val nowHour = hourOf(now)
        return WidgetArt.render((wDp * d).toInt(), (hDp * d).toInt(), d) {
            val gap = 7f * density
            val cw = (size.width - gap * 6) / 7
            val h = size.height
            days.forEachIndexed { i, day ->
                val x = i * (cw + gap)
                val today = i == days.lastIndex
                val (a, b) = when { day.hour >= 0 -> skyFor(day.hour); today -> skyFor(nowHour); else -> Color(0xFF3A3A52) to Color(0xFF55556E) }
                val rr = CornerRadius(14f * density)
                drawRoundRect(Brush.verticalGradient(listOf(a, b), startY = 0f, endY = h), Offset(x, 0f), Size(cw, h), rr)
                val cs = min(cw * .94f, h * .62f)
                val img = if (day.min > 0 || today) imgs[i] else nap
                if (img != null && s.catsOn) catAt(img, x + cw / 2, h - 3f * density, cs, null, if (day.min > 0 || today) 1f else .5f)
                if (today) drawRoundRect(tealEdge, Offset(x + density, density), Size(cw - 2 * density, h - 2 * density), CornerRadius(13f * density), style = Stroke(2f * density, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density))))
            }
        }
    }

    /**
     * The friends' table: a round table seen from above with up to three kittens around it, the one who's focusing with a
     * ring (the time they have left, out of an hour), a break a little dimmer, one not focusing dimmer still.
     */
    fun table(ctx: Context, sizeDp: Float, kittens: List<Triple<String, Float, Float?>>, dark: Boolean, ringColor: Color): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt()
        val imgs = kittens.map { WidgetArt.cat(ctx, it.first) }
        return WidgetArt.render(px, px, d) {
            val u = size.minDimension / 138f
            val c = Offset(69f * u, 69f * u)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF5A4E63), Color(0xFF3A3340)), center = Offset(c.x - 8f * u, c.y - 10f * u), radius = 60f * u), 47f * u, c)
            drawCircle(Color.White.copy(alpha = .07f), 45f * u, c, style = Stroke(4f * u))
            val spots = listOf(Triple(46f, 30f, 40f), Triple(104f, 78f, 44f), Triple(42f, 108f, 48f))
            kittens.forEachIndexed { i, k ->
                if (i >= spots.size) return@forEachIndexed
                val (cx, cy, sz) = spots[i]
                val (_, alpha, ring) = k
                if (ring != null) {
                    val r = 25f * u
                    drawCircle(ringColor.copy(alpha = .25f), r, Offset(cx * u, cy * u), style = Stroke(4f * u))
                    drawArc(ringColor, -90f, 360f * ring.coerceIn(.04f, 1f), false, Offset(cx * u - r, cy * u - r), Size(2 * r, 2 * r), style = Stroke(4f * u, cap = StrokeCap.Round))
                }
                imgs[i]?.let { catAt(it, cx * u, cy * u + sz * u / 2, sz * u, null, alpha) }
            }
        }
    }

    /** One cat painting on its own, `sizeDp` square, with Boo's glow if asked (the clock's cat). */
    fun catPicture(ctx: Context, path: String, sizeDp: Float, glow: Boolean): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt()
        val img = WidgetArt.cat(ctx, path)
        return WidgetArt.render(px, px, d) {
            if (img != null) {
                val u = size.minDimension / 118f
                val inner = size.minDimension - 12f * u
                if (glow) glow(img, size.width / 2, size.height / 2 + inner / 2, inner, u)
                catAt(img, size.width / 2, size.height / 2 + inner / 2, inner)
            }
        }
    }

    /** The clock's face, with its hands at a time (the Widget lab, where the real clock would drift). */
    fun clockFace(ctx: Context, sizeDp: Float, hour: Int, minute: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt()
        return WidgetArt.render(px, px, d) {
            val u = size.minDimension / 96f
            val c = center
            drawCircle(Color(0xFF3A3340), 45f * u, c)
            drawCircle(Color(0xFFD9A76B), 45f * u, c, style = Stroke(4f * u))
            drawCircle(Color(0xFFF6ECDD), 38f * u, c)
            for ((a, b) in listOf(Offset(48f, 14f) to Offset(48f, 20f), Offset(48f, 76f) to Offset(48f, 82f), Offset(14f, 48f) to Offset(20f, 48f), Offset(76f, 48f) to Offset(82f, 48f)))
                drawLine(Color(0xFF8A6A4A), Offset(a.x * u, a.y * u), Offset(b.x * u, b.y * u), 2f * u, StrokeCap.Round)
            fun hand(deg: Float, len: Float, width: Float) = rotate(deg, c) { drawLine(Color(0xFF2B211A), c, Offset(c.x, c.y - len * u), width * u, StrokeCap.Round) }
            hand(((hour % 12) + minute / 60f) * 30f, 22f, 4f)
            hand(minute * 6f, 32f, 2.6f)
            drawCircle(Color(0xFFE98A3C), 3.4f * u, c)
        }
    }
}
