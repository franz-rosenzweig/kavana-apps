package xyz.tiresia.kavana.wear

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The app itself: what the tile shows, as a screen (for when you open Chataltul from the watch's list of apps). */
class WatchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onResume() {
        super.onResume()
        show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun show() {
        val s = WatchStore.snap(this)
        if (s == null) scope.launch { Phone.act(this@WatchActivity, "hello") }
        // a round screen: one column in the middle, narrow enough for the circle (a 192 dp Pixel Watch), like the tile
        val w = resources.displayMetrics.widthPixels
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding((w * 0.14f).toInt(), (w * 0.1f).toInt(), (w * 0.14f).toInt(), (w * 0.1f).toInt()) }
        fun picture() = WatchStore.window(this)?.let { b -> col.addView(ImageView(this).apply { setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)) }, LinearLayout.LayoutParams(px(44), px(44))) }
        fun line(t: String, sp: Float, color: Int, lines: Int = 2) = col.addView(TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); gravity = Gravity.CENTER; maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        fun pill(t: String, id: String, round: Boolean = false) = Button(this).apply {
            text = t; isAllCaps = false; setTextColor(if (round) Color.WHITE else 0xFF10302B.toInt()); minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { cornerRadius = px(24).toFloat(); setColor(if (round) 0xFF2E2A36.toInt() else 0xFF5FD0C0.toInt()) }
            setOnClickListener { scope.launch { Phone.act(this@WatchActivity, id) } }
        }
        fun button(t: String, id: String) = col.addView(pill(t, id), LinearLayout.LayoutParams(px(96), px(36)).apply { topMargin = px(8) })
        fun rounds(vararg b: Pair<String, String>) = col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            b.forEach { (t, id) -> addView(pill(t, id, round = true), LinearLayout.LayoutParams(px(36), px(36)).apply { marginStart = px(4); marginEnd = px(4) }) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        fun pair(a: Pair<String, String>, b: Pair<String, String>) = col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(pill(a.first, a.second), LinearLayout.LayoutParams(px(64), px(36)).apply { marginEnd = px(3) })
            addView(pill(b.first, b.second, round = true), LinearLayout.LayoutParams(px(64), px(36)).apply { marginStart = px(3) })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
        val name = if (s?.catsOn != false) s?.catName ?: "" else "Chataltul"
        when (s?.mode) {
            null -> line("Waiting for the phone", 15f, Color.LTGRAY)
            "FOCUSING", "EXTRA", "GONE" -> {
                picture()
                line(when { s.over != null -> "Extra time"; s.stopwatch -> "Focusing"; else -> "${((s.end - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(0)} min left" }, 20f, Color.WHITE)
                if (s.title.isNotEmpty()) line(s.title, 13f, Color.LTGRAY, 1)
                if (s.finishes(System.currentTimeMillis())) button("Done", "stop") else button("Pause", "pause")
            }
            "CHECKIN" -> { line(if (s.title.isNotEmpty()) "Still on: ${s.title}?" else "Still focusing?", 15f, Color.WHITE); pair("Yes" to "yes", "Drifted" to "drifted") }
            "PAUSED" -> { picture(); line("Paused", 18f, Color.WHITE); button("Resume", "resume") }
            "HOME", "STOPPED" -> { picture(); line(if (s.mode == "HOME") "$name came home" else "Stopped early", 16f, Color.WHITE); button("Done", "done") }
            else -> { picture(); line(if (s.mode == "BREAK") "Break" else if (s.catsOn) "$name is waiting" else "Ready", 15f, Color.WHITE); rounds("15" to "start15", "50" to "start50", "∞" to "startInf"); button("Start 25", "start25") }
        }
        // the crown scrolls it (a ScrollView takes rotary input once it has focus)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); isFocusableInTouchMode = true; isFillViewport = true; addView(col, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)); requestFocus() })
    }
}
