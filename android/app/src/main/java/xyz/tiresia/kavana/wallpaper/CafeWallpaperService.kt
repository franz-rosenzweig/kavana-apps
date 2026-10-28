package xyz.tiresia.kavana.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.tiresia.kavana.Art
import xyz.tiresia.kavana.BuildConfig
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.UiState
import xyz.tiresia.kavana.op
import xyz.tiresia.kavana.ui.Cafe3D
import xyz.tiresia.kavana.ui.cafeHash
import xyz.tiresia.kavana.ui.cafeScene
import xyz.tiresia.kavana.ui.wallFor
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * The café as a live wallpaper: the app's own 3D café, floating on the café wall by day and under the stars at night, with today's
 * cats in it and your cat going along above the dock. To be light on the battery it moves only for a few seconds when it comes into
 * view, when you swipe between home screens or tap it, then holds still and draws once a minute; out of sight it draws nothing.
 * It never writes what a block is called (it may be the lock screen too).
 */
class CafeWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = CafeEngine()

    companion object {
        private const val TAG = "CafeWallpaper"

        /** Opens the system's preview of this wallpaper, with its own "Set wallpaper" (or the list of live wallpapers). */
        fun choose(ctx: Context) {
            val pick = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, CafeWallpaperService::class.java))
            runCatching { ctx.startActivity(pick) }.onFailure { runCatching { ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } }
        }
    }

    private inner class CafeEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val dens = resources.displayMetrics.density
        private val cafe = Cafe3D(this@CafeWallpaperService, dens, false)
        private var scope: CoroutineScope? = null
        private var st: UiState? = null
        private var visible = false
        private var w = 0
        private var h = 0
        private var bg: Bitmap? = null
        private var bgNight: Boolean? = null
        private var activeUntil = 0L
        private var last = 0L
        private var t = 0.0
        private var walkX = -1f
        private var walkDir = 1f
        private var walker: Bitmap? = null
        private var frames = 0
        private val frame = Runnable { drawFrame() }
        private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val pic = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        /** The hour the wallpaper goes by: the clock's (debug builds: or the widgets' check's, to see it by day and at night). */
        private fun hour(): Int {
            val forced = if (BuildConfig.DEBUG) KavanaApp.repo.prefs.getInt("wallpaperHour", -1) else -1
            return if (forced >= 0) forced else Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        }
        private fun night(): Boolean = hour().let { it >= 20 || it < 6 }
        /** The café's light for that hour, as the app's café has it by the clock: morning, day, evening, night. */
        private fun light(): String = when (hour()) { in 5..9 -> "morning"; in 10..16 -> "day"; in 17..20 -> "evening"; else -> "night" }
        private var sceneLight = ""
        private fun fps(): Int = if (getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) 12 else 24
        private val cafeTop get() = h * .27f
        private val cafeHeight get() = h * .4f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            cafe.margin = 8.0
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = s
            s.launch {
                val repo = KavanaApp.repo
                if (repo.state.value == null) withContext(Dispatchers.Default) { repo.act(op("tick")) }
                repo.state.collect { u -> if (u != null) { st = u; showScene(u); wake(6_000L) } }
            }
        }

        override fun onDestroy() {
            scope?.cancel()
            main.removeCallbacksAndMessages(null)
            super.onDestroy()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (BuildConfig.DEBUG) Log.i(TAG, "visible=$v frames=$frames")
            if (v) { last = 0L; wake(12_000L) } else main.removeCallbacks(frame)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height; bg = null
            wake(4_000L)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            main.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        // swiping between home screens turns the café a little
        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
            val goal = PI / 4 + (xOffset - .5) * .6
            if (abs(goal - cafe.goal) > 1e-3) { cafe.goal = goal; wake(3_000L) }
        }

        // a tap on the home screen where there's nothing else: a cat that's tapped does what it does in the app
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) { cafe.tap(x.toFloat(), y - cafeTop); wake(8_000L) }
            return null
        }

        private fun showScene(u: UiState) {
            val today0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val dark = night()
            sceneLight = light()
            cafe.show(cafeScene(u, u.history.filter { it.start >= today0 }, "day", cafeHash("wallpaper$today0"), dark, quiet = true).copy(light = sceneLight, dim = dark))
            val a = u.view.active
            walker = if (!u.catsOn) null else Art.bitmap(this@CafeWallpaperService, if (a != null) Art.kitten(a.cat) else Art.portrait(u.companion))
        }

        /** Move for this long from now (it's still otherwise). */
        private fun wake(ms: Long) {
            activeUntil = max(activeUntil, SystemClock.uptimeMillis() + ms)
            if (visible) { main.removeCallbacks(frame); main.post(frame) }
        }

        private fun drawFrame() {
            main.removeCallbacks(frame)
            if (!visible || w == 0 || h == 0) return
            val now = SystemClock.uptimeMillis()
            val active = now < activeUntil
            val dt = if (last == 0L || !active) 0.0 else ((now - last) / 1000.0).coerceAtMost(.1)
            last = if (active) now else 0L
            if (active) { cafe.step(dt); t += dt; walk(dt) }
            val c = runCatching { surfaceHolder.lockHardwareCanvas() }.getOrNull() ?: return
            try { paint(c, active) } finally { runCatching { surfaceHolder.unlockCanvasAndPost(c) } }
            frames++
            if (BuildConfig.DEBUG && (!active || frames % 48 == 0)) Log.i(TAG, "frames=$frames active=$active")
            // moving: the next frame; still: the next minute (the countdown, the day turning to night)
            main.postDelayed(frame, if (active) 1000L / fps() else 60_050L - System.currentTimeMillis() % 60_000L)
        }

        private fun paint(c: Canvas, active: Boolean) {
            val dark = night()
            if (bg == null || bgNight != dark) { bg = background(dark); bgNight = dark }
            if (light() != sceneLight) st?.let { showScene(it) } // the hour turned: the café's light with it
            c.drawBitmap(bg!!, 0f, 0f, null)
            c.save()
            c.translate(0f, cafeTop)
            cafe.resize(w.toFloat(), cafeHeight)
            cafe.draw(c)
            c.restore()
            st?.view?.active?.let { a -> // a block: how long is left, never what it's called
                val left = ((a.end - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(1)
                val line = when { a.paused != null -> "Paused"; a.mode == "stopwatch" -> "Focusing"; else -> "Focusing · $left min left" }
                words.textSize = 16f * resources.displayMetrics.scaledDensity
                words.color = if (dark) 0xFFF2EDE6.toInt() else 0xFF2B211A.toInt()
                c.drawText(line, w / 2f, h * .21f, words)
            }
            walker?.let { b ->
                val size = 82f * dens
                val x = if (walkX < 0) w * .3f else walkX
                val hop = if (active) abs(sin(t * 6)).toFloat() * 5f * dens else 0f
                val y = h * .8f - size - hop
                c.save()
                if (walkDir < 0) c.scale(-1f, 1f, x + size / 2, y + size / 2)
                c.drawBitmap(b, null, RectF(x, y, x + size, y + size), pic)
                c.restore()
            }
        }

        /** Your cat goes along above the dock and turns at the ends. */
        private fun walk(dt: Double) {
            val size = 82f * dens
            val lo = w * .12f
            val hi = w * .88f - size
            if (walkX < 0) walkX = w * .3f
            walkX += (walkDir * 30f * dens * dt).toFloat()
            if (walkX > hi) { walkX = hi; walkDir = -1f }
            if (walkX < lo) { walkX = lo; walkDir = 1f }
        }

        /** The café wall with its dots over a wooden floor by day; a starry sky over a dark floor at night. Drawn once per size and time of day. */
        private fun background(dark: Boolean): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val split = h * if (dark) .6f else .72f
            if (dark) {
                p.shader = LinearGradient(0f, 0f, 0f, split, 0xFF141A3E.toInt(), 0xFF2B2E5E.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), split, p)
                p.shader = LinearGradient(0f, split, 0f, h.toFloat(), 0xFF2A2233.toInt(), 0xFF1C1722.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, split, w.toFloat(), h.toFloat(), p)
                p.shader = null
                val r = java.util.Random(3)
                repeat(70) {
                    p.color = android.graphics.Color.argb(90 + r.nextInt(150), 255, 255, 255)
                    c.drawCircle(r.nextFloat() * w, r.nextFloat() * split * .95f, (.6f + r.nextFloat() * .9f) * dens, p)
                }
            } else {
                val wall = wallFor(st?.view?.settings?.theme ?: "classic", false)
                p.shader = LinearGradient(0f, 0f, 0f, split, wall.top.toArgb(), wall.bottom.toArgb(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), split, p)
                p.shader = LinearGradient(0f, split, 0f, h.toFloat(), 0xFFE2C198.toInt(), 0xFFD4AE7E.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, split, w.toFloat(), h.toFloat(), p)
                p.shader = null
                p.color = wall.dots.toArgb()
                val step = 26f * dens
                val r = wall.dot.value * dens
                var y = step / 2
                while (y < split - step / 3) { var x = step / 2; while (x < w) { c.drawCircle(x, y, r, p); x += step }; y += step }
                p.color = 0x22603A1A
                c.drawRect(0f, split, w.toFloat(), split + 2 * dens, p)
            }
            return b
        }
    }
}
