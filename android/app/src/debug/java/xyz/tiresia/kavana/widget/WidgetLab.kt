package xyz.tiresia.kavana.widget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug builds only: a screen that hosts the app's own widgets at real sizes, each with a made-up state, so they
 * can be looked at (and tapped) without the phone's home screen, whose widgets and notifications are private.
 *
 *   adb shell am start -n xyz.tiresia.kavana.debug/xyz.tiresia.kavana.widget.WidgetLab --es set window
 *
 * The first time, Android asks "Allow Chataltul dev to create widgets?": that's the one tap it needs.
 *
 * `--es set` is one of the sets below; `--ez live true` hosts one widget on the real snapshot instead (to tap it).
 */
class WidgetLab : Activity() {
    private class Demo(val title: String, val provider: Class<*>, val w: Int, val h: Int, val snap: Snap?)

    private fun sets(set: String): List<Demo> {
        val win = WindowWidgetReceiver::class.java
        val now = System.currentTimeMillis()
        fun d(t: String, w: Int, h: Int, mode: String, theme: String = "classic", cat: String = "miso", name: String = "Miso") = Demo(t, win, w, h, Demos.snap(mode, now, theme, cat, name))
        return when (set) {
            "window" -> listOf("ready", "focusing", "checkin", "home", "paused", "gone").map { d(it, 340, 150, it) }
            "window-more" -> listOf("nap", "grown", "notitle", "stopped", "extra", "break").map { d(it, 340, 150, it) }
            "small" -> listOf("ready", "focusing", "checkin", "home", "paused", "gone").map { d(it, 150, 150, it) }
            "strip" -> listOf("ready", "focusing", "checkin", "home", "paused", "gone").map { d(it, 340, 64, it) }
            "quick" -> listOf("ready", "focusing", "checkin", "home").map { Demo("quick start: $it", QuickStartWidgetReceiver::class.java, 340, 80, Demos.snap(it, now)) } +
                Demo("quick start: ready, dark cat off", QuickStartWidgetReceiver::class.java, 340, 80, Demos.snap("ready", now).copy(catsOn = false))
            "today" -> listOf("ready", "nocollect", "empty").map { Demo("today: $it", TodayWidgetReceiver::class.java, 340, 150, Demos.snap(it, now)) } +
                Demo("today: narrow", TodayWidgetReceiver::class.java, 280, 150, Demos.snap("ready", now))
            "later" -> listOf("ready", "empty").map { Demo("later: $it", LaterWidgetReceiver::class.java, 340, 180, Demos.snap(it, now)) } +
                Demo("later: short", LaterWidgetReceiver::class.java, 340, 130, Demos.snap("ready", now))
            "sound" -> listOf("focusing", "muted", "ready", "empty").map { Demo("sound: $it", SoundWidgetReceiver::class.java, 340, 72, Demos.snap(it, now)) }
            "milo" -> listOf(d("milo ready", 340, 150, "ready", cat = "pepper", name = "Milo"), d("milo focusing", 340, 150, "focusing", cat = "pepper", name = "Milo"), d("milo home", 340, 150, "home", cat = "pepper", name = "Milo"))
            "themes" -> listOf("classic", "mint", "sakura", "library", "night").map { d(it, 340, 150, "focusing", it) }
            else -> emptyList()
        }
    }

    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dens = resources.displayMetrics.density
        fun px(dp: Number) = (dp.toFloat() * dens).toInt()
        val mgr = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, 4711)
        host.deleteHost()
        // Hosting widgets needs the user's OK, once (Android's own dialog); then this screen starts again.
        val probe = host.allocateAppWidgetId()
        if (!mgr.bindAppWidgetIdIfAllowed(probe, ComponentName(this, WindowWidgetReceiver::class.java))) {
            startActivityForResult(android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, probe)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, ComponentName(this, WindowWidgetReceiver::class.java)), 1)
            return
        }
        host.deleteAppWidgetId(probe)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), px(30), px(10), px(24)); setBackgroundColor(Color.parseColor("#E6E2DA")) }
        val ids = HashMap<Int, Snap>()
        val live = intent.getBooleanExtra("live", false)
        val demos = if (live) listOf(Demo("live", WindowWidgetReceiver::class.java, intent.getIntExtra("w", 340), intent.getIntExtra("h", 150), null)) else sets(intent.getStringExtra("set") ?: "window")
        val bound = ArrayList<Int>()
        for (dm in demos) {
            val id = host.allocateAppWidgetId()
            // The host view pads a widget by 8 dp on every side, as a launcher's cell does: the widget gets what's left.
            val ow = dm.w - 16; val oh = dm.h - 16
            val opts = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, ow); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, ow)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, oh); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, oh)
            }
            val ok = mgr.bindAppWidgetIdIfAllowed(id, ComponentName(this, dm.provider), opts)
            col.addView(TextView(this).apply { text = dm.title + "  (${dm.w}×${dm.h})"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(Color.parseColor("#5F5850")); setPadding(px(6), px(8), 0, px(2)) })
            if (!ok) { col.addView(TextView(this).apply { text = "not allowed to host widgets" }); continue }
            val info = mgr.getAppWidgetInfo(id)
            val view: AppWidgetHostView = host.createView(this, id, info)
            view.setAppWidget(id, info)
            col.addView(view, LinearLayout.LayoutParams(px(dm.w), px(dm.h)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            view.updateAppWidgetSize(opts, ow, oh, ow, oh)
            dm.snap?.let { ids[id] = it }
            bound += id
        }
        WidgetStore.setDemo(this, ids)
        setContentView(ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setBackgroundColor(Color.parseColor("#E6E2DA"))
            // edge to edge: keep the first label out from under the status bar, whatever its height
            setOnApplyWindowInsetsListener { _, ins ->
                val bars = ins.getInsets(WindowInsets.Type.systemBars())
                col.setPadding(px(10), bars.top + px(8), px(10), bars.bottom + px(24))
                ins
            }
        })
        host.startListening()
        WidgetUpdater.refresh(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) recreate()
    }

    override fun onDestroy() {
        runCatching { if (::host.isInitialized) host.stopListening() }
        super.onDestroy()
    }
}
