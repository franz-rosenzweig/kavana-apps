package xyz.tiresia.kavana

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent

/**
 * The cat over other apps: it peeks up from the bottom of the screen with a speech bubble.
 * - You leave Kavana in the middle of a block: "Boo! It's time to focus.", Back to work / Let me in.
 * - A check-in comes while you're in another app: "Boo! Still on: …?", Yes / Drifted right there.
 * - A nudge, the end of a block, a break's end.
 * Needs "Display over other apps". One cat at a time; a new one takes the place of the old.
 */
object CatOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var kind: String? = null
    private var timer: Runnable? = null
    private var wm: WindowManager? = null

    fun allowed(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** "cats/miso-1.webp" -> "miso". */
    private fun catOf(picture: String) = picture.substringAfterLast('/').substringBefore("-")

    private class Btn(val label: String, val primary: Boolean, val onClick: () -> Unit)

    fun leave(ctx: Context, st: UiState, a: Block) {
        val c = st.cat(a.cat)
        show(ctx, "leave", Art.portrait(c.id), "Boo! It's time to focus.", "${c.name} is waiting. Come back, or ${c.name} wanders off.",
            listOf(Btn("Back to work", true) { openApp(ctx) }, Btn("Let me in", false) {}), Repo.GRACE) {
            // Still away when the grace is over: say so, briefly (the doc learns it when you're back).
            if (KavanaApp.repo.prefs.getLong("left", 0) != 0L && !MainActivity.visible) {
                show(ctx, "gone", Art.GONE, "${c.name} wandered off.", "Your block keeps going.", emptyList(), 4_000) {}
            }
        }
    }

    fun ping(ctx: Context, p: Ping, st: UiState) {
        val a = st.view.active
        val w = st.view.awaiting
        val catId = a?.cat ?: w?.cat ?: st.companion
        val title = p.title.removePrefix("🐱 ")
        val later = { Notify.ping(ctx, p, st) } // not answered on the cat: it waits in the notifications
        when (p.kind) {
            "checkin" -> {
                val id = p.block ?: return Notify.ping(ctx, p, st)
                val n = p.n ?: 0
                show(ctx, "checkin", Art.portrait(catId), title, p.body.removeSuffix(" Tap to answer."),
                    listOf(
                        Btn("Yes", true) { answer(id, n, "yes") },
                        Btn("Drifted", false) {
                            answer(id, n, "drifted")
                            show(ctx, "thanks", Art.portrait(catId), "No worries.", "Back to it.", emptyList(), 2_500) {}
                        },
                    ), 90_000, later)
            }
            "nudge" -> show(ctx, "nudge", Art.portrait(catId), p.body, a?.intention, emptyList(), 9_000) {}
            "end" -> {
                val id = p.block ?: return Notify.ping(ctx, p, st)
                val c = st.cat(catId)
                val says = when {
                    w == null || !st.catsOn -> null
                    w.kitten == "home" -> "${c.name} came home to your café. 🐟 +${w.treats}"
                    else -> "${c.name} wandered off."
                }
                show(ctx, "end", Art.portrait(catId), title, listOfNotNull(w?.intention, says).joinToString("\n"),
                    listOf(
                        Btn("Done", true) { outcome(id, "done") },
                        Btn("Partly", false) { outcome(id, "partly") },
                        Btn("Not really", false) { outcome(id, "notreally") },
                    ), 60_000, later)
            }
            "break-over" -> show(ctx, "break", Art.portrait(catId), title, "It's time to focus. Ready for the next block?",
                listOf(Btn("Open Kavana", true) { openApp(ctx) }), 30_000, later)
            else -> Notify.ping(ctx, p, st)
        }
    }

    /** A test from Settings: what the cat looks like over another app. */
    fun test(ctx: Context, catId: String) = show(ctx, "test", Art.portrait(catId), "Boo! It's time to focus.",
        "This is how I'll find you in other apps.", listOf(Btn("Nice!", true) {}), 8_000) {}

    private fun answer(id: String, n: Int, a: String) = KavanaApp.repo.launch(op("answer", "id" to id, "n" to n, "answer" to a))
    private fun outcome(id: String, o: String) = KavanaApp.repo.launch(op("outcome", "id" to id, "outcome" to o)) { Notify.cancel(KavanaApp.app, Notify.ID_PING) }

    private fun openApp(ctx: Context) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Takes the cat away (only if it's still saying `which`, when given). */
    fun hide(which: String? = null) = main.post {
        if (which != null && kind != which) return@post
        timer?.let { main.removeCallbacks(it) }
        timer = null
        val v = root ?: return@post
        root = null
        kind = null
        runCatching { wm?.removeView(v) } // the window's own animation slides it away
    }

    private fun removeNow() {
        timer?.let { main.removeCallbacks(it) }
        timer = null
        root?.let { v -> runCatching { wm?.removeView(v) } }
        root = null
        kind = null
    }

    private fun show(ctx: Context, which: String, picture: String, title: String, body: String?, buttons: List<Btn>, hideAfter: Long, onTimeout: () -> Unit) {
        main.post {
            removeNow()
            val app = ctx.applicationContext
            val w = app.getSystemService(WindowManager::class.java)
            wm = w
            val (v, cat) = build(app, w, picture, title, body, buttons, which)
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                fitInsetsTypes = 0
                setTitle("Kavana")
                // The slide up and down is the window's, played by Android. A view animation stalls
                // while Kavana is in the background (seen on the S24+), which left the cat below the
                // edge: there, but out of sight.
                windowAnimations = android.R.style.Animation_InputMethod
            }
            try { w.addView(v, lp) } catch (e: Exception) { Log.w(Repo.TAG, "cat", e); onTimeout(); return@post }
            root = v
            kind = which
            // Once it's up, the cat reacts: a hop for Boo!, a curious tilt for a question. It lives
            // (breathes, blinks) as long as frames come; if they stall, it's still in plain sight.
            main.postDelayed({
                (cat as? LivingCatView)?.cat?.run {
                    flick(0); flick(1)
                    if (which == "leave" || which == "test") hop() else tilt(Motion.HEAD_TILT, 1.6f)
                }
            }, 380)
            timer = Runnable { hide(which); onTimeout() }.also { main.postDelayed(it, hideAfter) }
        }
    }

    private fun build(ctx: Context, w: WindowManager, picture: String, title: String, body: String?, buttons: List<Btn>, which: String): Pair<View, View> {
        val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val d = ctx.resources.displayMetrics.density
        fun dp(x: Number) = (x.toFloat() * d).toInt()
        val surface = if (dark) 0xFF1C2120.toInt() else Color.WHITE
        val ink = if (dark) 0xFFECE7DE.toInt() else 0xFF1E2627.toInt()
        val muted = if (dark) 0xFF9AA29F.toInt() else 0xFF5F6866.toInt()
        val line = if (dark) 0xFF2B3431.toInt() else 0xFFE2DED6.toInt()
        val tint = if (dark) 0xFF5FD0C0.toInt() else 0xFF2C8A7E.toInt()
        val onTint = if (dark) 0xFF04201C.toInt() else Color.WHITE
        val tonal = if (dark) 0xFF23332F.toInt() else 0xFFDCEBE7.toInt()
        val onTonal = if (dark) 0xFFA6F2E5.toInt() else 0xFF1F5E57.toInt()
        val nav = w.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
        // A phone under 400 points wide (the S24+ is 384) gets tighter buttons, so "Let me in" and
        // "Not really" aren't cut short.
        val narrow = ctx.resources.configuration.screenWidthDp < 400
        val btnText = if (narrow && buttons.size > 2) 14f else 15f
        val btnPad = when { buttons.size > 2 -> if (narrow) 10 else 12; narrow -> 14; else -> 18 }

        val root = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false; setPadding(dp(12), dp(10), dp(8), 0) }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(surface); setStroke(dp(1), line) }
            elevation = dp(10).toFloat()
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        head.addView(TextView(ctx).apply {
            text = title; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (title.length > 70) 16f else 19f)
            typeface = Typeface.create(Typeface.DEFAULT, 650, false); setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(2) })
        head.addView(TextView(ctx).apply {
            text = "×"; setTextColor(muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
            contentDescription = "Close"; setOnClickListener { hide(which) }
        }, LinearLayout.LayoutParams(dp(36), dp(32)).apply { marginStart = dp(4); topMargin = dp(-4) })
        card.addView(head)
        if (!body.isNullOrBlank()) card.addView(TextView(ctx).apply {
            text = body; setTextColor(muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); marginEnd = dp(8) })
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.forEachIndexed { i, b ->
                row.addView(TextView(ctx).apply {
                    text = b.label; setTextColor(if (b.primary) onTint else onTonal); setTextSize(TypedValue.COMPLEX_UNIT_SP, btnText)
                    typeface = Typeface.create(Typeface.DEFAULT, 600, false); gravity = Gravity.CENTER; maxLines = 1
                    setPadding(dp(btnPad), dp(10), dp(btnPad), dp(10))
                    background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(if (b.primary) tint else tonal) }
                    setOnClickListener { hide(which); b.onClick() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(8) })
            }
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        } else card.setOnClickListener { hide(which) }

        // The bubble sits left of the cat, which peeks up from the very bottom of the screen.
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            marginEnd = dp(if (narrow) 100 else 112); bottomMargin = nav + dp(18)
        })
        val cat: View = if (picture == Art.GONE) ImageView(ctx).apply {
            setImageBitmap(Art.bitmap(ctx, picture)); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_END
            contentDescription = "Paw prints"; translationY = dp(30).toFloat()
        } else LivingCatView(ctx, catOf(picture)).apply {
            contentDescription = "A cat"
            // The view is the frame around the picture (Motion.FX0..): the picture itself is 136 dp, as before.
            translationY = dp(30 + 136 * (Motion.FH + Motion.FY0 - 512f) / 512f).toFloat(); translationX = dp(136 * -Motion.FX0 / 512f).toFloat()
        }
        val side = if (cat is LivingCatView) dp(136 * Motion.FW / 512f) else dp(136)
        root.addView(cat, FrameLayout.LayoutParams(side, if (cat is LivingCatView) side else dp(150), Gravity.BOTTOM or Gravity.END))
        return root to cat
    }
}

/** A living cat in a plain View, for the window over other apps (Compose isn't there). Tap it to make it hop. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class LivingCatView(ctx: Context, id: String) : View(ctx) {
    val cat = LivingCat(ctx, id, "portrait", grid = 30, shadow = false)
    private var last = 0L

    init {
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> cat.lookAtCanvas(e.x, e.y)
                MotionEvent.ACTION_UP -> { cat.look = null; cat.poke() }
                MotionEvent.ACTION_CANCEL -> cat.look = null
            }
            true
        }
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        cat.update(if (last == 0L) 0.016f else (now - last) / 1000f)
        last = now
        cat.draw(c, width.toFloat(), height.toFloat())
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}
