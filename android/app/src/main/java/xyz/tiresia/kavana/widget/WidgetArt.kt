package xyz.tiresia.kavana.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import xyz.tiresia.kavana.Art
import xyz.tiresia.kavana.ui.drawSky
import xyz.tiresia.kavana.ui.drawWall
import xyz.tiresia.kavana.ui.homeSky
import xyz.tiresia.kavana.ui.skyAt
import xyz.tiresia.kavana.ui.wallFor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pictures on the widgets, drawn into small bitmaps with the same code and the same paintings as the
 * app (the café wall, the sky, the ring, the cats), because a widget can't draw. Each is drawn at the size
 * it's shown at, so the corners and the dots are never stretched.
 */
object WidgetArt {
    private fun render(w: Int, h: Int, density: Float, draw: DrawScope.() -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), Size(w.toFloat(), h.toFloat()), draw)
        return bmp
    }

    private fun cat(ctx: Context, path: String): ImageBitmap? = Art.bitmap(ctx, path)?.asImageBitmap()

    /** Whether the wall is dark enough for white words (the library and night cafés, and any dark mode). */
    fun lightInk(theme: String, dark: Boolean) = wallFor(theme, dark).lightInk

    /** A widget's whole background: the café wall (a gradient with its dots), its corners rounded, and an optional outline. */
    fun wall(ctx: Context, wDp: Float, hDp: Float, theme: String, dark: Boolean, outline: Color? = null, cornerDp: Float = 30f): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val wall = wallFor(theme, dark)
        return render((wDp * d).toInt(), (hDp * d).toInt(), d) {
            val r = cornerDp * density
            clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }) { drawWall(wall) }
            if (outline != null) {
                val sw = 3f * density
                drawRoundRect(outline, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(r - sw / 2), style = Stroke(sw))
            } else if (wall.lightInk) {
                val sw = density
                drawRoundRect(Color.White.copy(alpha = .07f), Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(r - sw / 2), style = Stroke(sw))
            }
        }
    }

    /** A plain rounded card (the Today and Later widgets). */
    fun card(ctx: Context, wDp: Float, hDp: Float, fill: Color, cornerDp: Float = 30f, outline: Color? = null): Bitmap {
        val d = ctx.resources.displayMetrics.density
        return render((wDp * d).toInt(), (hDp * d).toInt(), d) {
            val r = cornerDp * density
            drawRoundRect(fill, cornerRadius = CornerRadius(r))
            if (outline != null) {
                val sw = density
                drawRoundRect(outline, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(r - sw / 2), style = Stroke(sw))
            }
        }
    }

    /** A progress ring (the Today widget's goal): a track, and an arc clockwise from the top. */
    fun ring(ctx: Context, sizeDp: Float, frac: Float, track: Color, tint: Color, strokeDp: Float): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt()
        return render(px, px, d) {
            val sw = strokeDp * density
            val r = (size.minDimension - sw) / 2
            val c = center
            drawCircle(track, r, c, style = Stroke(sw))
            if (frac > 0f) drawArc(tint, -90f, 360f * frac, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(sw, cap = StrokeCap.Round))
        }
    }

    /** A thin volume bar: a rounded track with the first `frac` of it filled. */
    fun bar(ctx: Context, wDp: Float, hDp: Float, frac: Float, track: Color, tint: Color): Bitmap {
        val d = ctx.resources.displayMetrics.density
        return render((wDp * d).toInt(), (hDp * d).toInt(), d) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            drawRoundRect(tint, size = Size(size.width * frac.coerceIn(0.05f, 1f), size.height), cornerRadius = r)
        }
    }

    private val CONFETTI = listOf(Color(0xFFE98A3C), Color(0xFF2C8A7E), Color(0xFFF2A17C), Color(0xFFFFCF8A), Color(0xFF8A64AD), Color(0xFF5FD0C0), Color.White)

    /**
     * The round window, a square `sizeDp` wide: the sky, the frame, the cushion and the cat, and when a block
     * is running the ring around it (what's left, clockwise from the top). Everything is in units of a
     * 140-wide square, like the mockups.
     */
    fun window(ctx: Context, s: Snap, now: Long, sizeDp: Float, dark: Boolean, mode: WMode = s.mode()): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt()
        val b = s.block
        val ring = mode == WMode.FOCUSING || mode == WMode.CHECKIN || mode == WMode.PAUSED || mode == WMode.GONE || mode == WMode.EXTRA
        val paused = mode == WMode.PAUSED
        val p = when (mode) {
            WMode.HOME, WMode.STOPPED -> if (s.awaiting?.kitten == "home") .66f else .5f
            WMode.BREAK -> .3f
            else -> b?.progress(now) ?: 0f
        }
        val stage = when {
            mode == WMode.READY -> "nap"
            mode == WMode.HOME -> "grown"
            mode == WMode.CHECKIN && b != null -> stageOf(b.grow(now))
            b != null -> if (b.kitten == "home" || b.over != null) "grown" else stageOf(b.grow(now))
            else -> "nap"
        }
        val catPath = when (stage) { "nap" -> s.nap; "kitten" -> s.kitten; else -> s.portrait }
        val frame = if (dark) Color(0xFF3A3340) else Color(0xFFFFF8EE)
        return render(px, px, d) {
            val u = size.minDimension / 140f
            val c = center
            val discR = (if (ring) 59f else 64f) * u
            val pane = discR * 2
            // the halo around a disc with no ring
            if (!ring) drawCircle(Color.White.copy(alpha = if (dark) .12f else .55f), 66.5f * u, c, style = Stroke(5f * u))
            // the disc: the sky, the frame, the window's bars
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, discR)) }) {
                when {
                    mode == WMode.READY || mode == WMode.BREAK -> {
                        val (a, bb) = homeSky(dark)
                        drawRect(Brush.verticalGradient(listOf(a, bb)))
                    }
                    else -> drawSky(p, paused, dark)
                }
                val lw = pane * 0.03f
                drawRect(frame.copy(alpha = .8f), Offset(c.x - lw / 2, c.y - discR), Size(lw, pane * 0.34f))
                drawRect(frame.copy(alpha = .8f), Offset(c.x - discR, c.y - discR + pane * 0.34f - lw / 2), Size(pane, lw))
                // the cushion and the cat on it, as on the app's home window
                val grey = if (paused) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(.45f) }) else null
                if (s.catsOn) {
                    val bottom = c.y + discR
                    if (mode == WMode.GONE || mode == WMode.STOPPED) {
                        cat(ctx, Art.GONE)?.let { img -> drawBitmap(img, c.x, bottom - pane * 0.02f, pane * 0.72f, null) }
                    } else {
                        cat(ctx, "cafe/cushion-1.webp")?.let { img -> drawBitmap(img, c.x, bottom - pane * 0.02f, pane * 0.72f, grey) }
                        cat(ctx, catPath)?.let { img -> drawBitmap(img, c.x, bottom - pane * 0.13f, pane * 0.56f, grey) }
                    }
                }
                drawCircle(frame, discR - pane * 0.0225f, c, style = Stroke(pane * 0.045f))
            }
            if (ring) {
                val tint = when { paused -> if (dark) Color(0xFFFFCF8A) else Color(0xFFE98A3C); dark -> Color(0xFF5FD0C0); else -> Color(0xFF2C8A7E) }
                val track = if (dark) Color.White.copy(alpha = .12f) else Color(124, 74, 34, 41)
                val r = 66f * u
                val frac = when {
                    b == null -> 1f
                    b.stopwatch -> 1f - ((b.paused ?: now).let { at -> (b.total - (b.end - at)) } % 3_600_000L) / 3_600_000f
                    b.over != null -> 0f
                    else -> ((b.end - (b.paused ?: now)).toFloat() / b.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                }
                drawCircle(track, r, c, style = Stroke(5f * u))
                drawArc(tint, -90f, 360f * frac, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(5f * u, cap = StrokeCap.Round))
                drawCircle(tint, 4.5f * u, Offset(c.x, c.y - r))
            }
            if (mode == WMode.HOME) {
                // the confetti burst: a fixed scatter of little paper bits, so it looks the same every time
                val rnd = java.util.Random(7)
                repeat(14) { i -> // inside the sky, above the cat
                    val x = c.x + (rnd.nextFloat() * 2 - 1) * 46f * u
                    val y = c.y - (6f + rnd.nextFloat() * 46f) * u
                    val at = Offset(x, y)
                    rotate(rnd.nextFloat() * 180f, at) {
                        drawRect(CONFETTI[(i + 3) % CONFETTI.size], Offset(at.x - 1.6f * u, at.y - 3.2f * u), Size(3.2f * u, 6.4f * u))
                    }
                }
                repeat(26) { i ->
                    val ang = rnd.nextFloat() * 2 * PI.toFloat()
                    val dist = (62f + rnd.nextFloat() * 8f) * u
                    val at = Offset(c.x + dist * cos(ang), c.y + dist * sin(ang) * 0.95f)
                    rotate(rnd.nextFloat() * 180f, at) {
                        drawRect(CONFETTI[i % CONFETTI.size], Offset(at.x - 1.6f * u, at.y - 3.2f * u), Size(3.2f * u, 6.4f * u))
                    }
                }
            }
        }
    }

    /** Draws the cat picture `w` wide, standing on `bottom`, centred on `cx`. */
    private fun DrawScope.drawBitmap(img: ImageBitmap, cx: Float, bottom: Float, w: Float, filter: ColorFilter?) {
        val wi = w.toInt()
        drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset((cx - w / 2).toInt(), (bottom - w).toInt()), dstSize = IntSize(wi, wi), filterQuality = FilterQuality.High, colorFilter = filter)
    }

    /** A small round avatar of the window (the slim strip): the sky and the cat, no ring. */
    fun avatar(ctx: Context, s: Snap, now: Long, sizeDp: Float, dark: Boolean): Bitmap = window(ctx, s, now, sizeDp, dark)
}
