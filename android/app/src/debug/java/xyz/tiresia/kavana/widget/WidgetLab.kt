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
 * `--es set` is one of the sets below (`--ei part N`: only its Nth four, or `--ei per M`: M at a time); `--ez live true` hosts one widget on the real snapshot instead (to tap it; `--es provider pet|clock|friends|cafe|…` picks which).
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
            "cafe" -> {
                val b = Demos.snap("ready", now)
                val none = b.copy(todayHome = 0, todayMin = 0, todayTreats = 0, monthHome = 0, monthMin = 0, monthGone = 0, monthTreats = 0)
                listOf(Demo("café 4x4", CafeWidgetReceiver::class.java, 340, 340, b), Demo("café 2x2", CafeWidgetReceiver::class.java, 150, 150, b), Demo("café 4x4: nothing yet", CafeWidgetReceiver::class.java, 340, 340, none), Demo("café 2x2: nothing yet", CafeWidgetReceiver::class.java, 150, 150, none))
            }
            "friends" -> {
                val b = Demos.snap("ready", now)
                fun f(t: String, fs: FriendsSnap) = Demo(t, FriendsWidgetReceiver::class.java, 340, 150, b.copy(friends = fs))
                val rows = listOf(FriendRow("dana", "Dana", "focusing", end = now + 14 * 60_000L), FriendRow("sam", "Sam", "break", end = now + 4 * 60_000L), FriendRow("alex", "Alex", "idle"))
                listOf(
                    f("three friends", FriendsSnap(true, true, rows, now)),
                    f("one focusing, one not sharing", FriendsSnap(true, true, listOf(rows[0], FriendRow("kim", "Kim", "hidden")), now)),
                    f("no friends yet", FriendsSnap(true, true, emptyList(), now)),
                    f("signed out", FriendsSnap(false)),
                    f("I'm not sharing", FriendsSnap(true, false, rows, now)),
                )
            }
            "sky" -> {
                val b = Demos.snap("ready", now)
                val none = b.copy(stars = emptyList(), days = Demos.days(empty = true), streak = 0)
                listOf(
                    Demo("constellation: the month", ConstellationWidgetReceiver::class.java, 340, 150, b), Demo("constellation: no stars yet", ConstellationWidgetReceiver::class.java, 340, 150, none),
                    Demo("constellation: the last two weeks", ConstellationWidgetReceiver::class.java, 150, 150, b), Demo("constellation: two weeks, nothing", ConstellationWidgetReceiver::class.java, 150, 150, none),
                    Demo("week as weather", WeatherWidgetReceiver::class.java, 340, 150, b), Demo("week as weather: nothing yet", WeatherWidgetReceiver::class.java, 340, 150, none),
                )
            }
            "pet" -> {
                val day = Demos.snap("ready", now)
                fun pet(t: String, w: Int, h: Int, s: Snap, hour: Int, pose: Int) = Demo(t, PetCatWidgetReceiver::class.java, w, h, s.copy(demo = SnapDemo(Demos.at(hour, 20), pose)))
                listOf(
                    pet("2x2: sitting", 150, 150, day, 14, 0), pet("2x2: tapped, she hops", 150, 150, day, 14, 1), pet("2x2: tapped again, she purrs", 150, 150, day, 14, 2),
                    pet("2x2: late, Boo asleep", 150, 150, day, 23, 0), pet("2x2: late, Boo purring in his sleep", 150, 150, day, 23, 2),
                    pet("2x2: late, no Boo yet", 150, 150, day.copy(boo = false), 23, 0),
                    pet("2x2: Milo, mint", 150, 150, Demos.snap("ready", now, "mint", "pepper", "Milo"), 14, 0), pet("2x2: Biscuit, sakura", 150, 150, Demos.snap("ready", now, "sakura", "biscuit", "Biscuit"), 14, 0),
                    pet("2x2: Earl Grey, library", 150, 150, Demos.snap("ready", now, "library", "earl", "Earl Grey"), 14, 0),
                    pet("4x2: the sill", 340, 150, day, 14, 0), pet("4x2: she hops", 340, 150, day, 14, 1), pet("4x2: purring", 340, 150, day, 14, 2),
                    pet("4x2: a block is running", 340, 150, Demos.snap("focusing", now), 14, 0), pet("4x2: late, asleep", 340, 150, day, 23, 0),
                    pet("4x1: peeking", 340, 70, day, 14, 0), pet("4x1: she hops", 340, 70, day, 14, 1), pet("4x1: a block is running", 340, 70, Demos.snap("focusing", now), 14, 0), pet("4x1: late", 340, 70, day, 23, 0),
                )
            }
            "clock" -> {
                val day = Demos.snap("ready", now)
                fun clock(t: String, s: Snap, hour: Int, minute: Int) = Demo(t, ClockWidgetReceiver::class.java, 340, 150, s.copy(demo = SnapDemo(Demos.at(hour, minute))))
                listOf(
                    clock("morning", day, 8, 15), clock("afternoon, a block running", Demos.snap("focusing", now), 14, 30), clock("evening", day, 19, 5),
                    clock("late, Boo is out", day, 22, 42), clock("late, no Boo yet", day.copy(boo = false), 23, 10), clock("a block came home", Demos.snap("home", now), 15, 0),
                )
            }
            "stats" -> {
                val b = Demos.snap("ready", now)
                fun st(t: String, c: Class<*>, w: Int, h: Int, s: Snap) = Demo(t, c, w, h, s)
                listOf(
                    st("streak: 5", StreakWidgetReceiver::class.java, 150, 150, b), st("streak: nothing yet", StreakWidgetReceiver::class.java, 150, 150, b.copy(streak = 0)), st("streak: 12", StreakWidgetReceiver::class.java, 150, 150, b.copy(streak = 12)),
                    st("goal: 80 of 120", GoalWidgetReceiver::class.java, 150, 150, b), st("goal: nothing yet", GoalWidgetReceiver::class.java, 150, 150, b.copy(todayMin = 0)),
                    st("goal: reached", GoalWidgetReceiver::class.java, 150, 150, b.copy(todayMin = 125)), st("goal: none set", GoalWidgetReceiver::class.java, 150, 150, b.copy(goalMin = 0)),
                    st("missions: 2 of 3, a reward", MissionsWidgetReceiver::class.java, 340, 70, b),
                    st("missions: all done", MissionsWidgetReceiver::class.java, 340, 70, b.copy(missionsDone = 3, missionNext = "", collect = emptyList())),
                    st("missions: none yet", MissionsWidgetReceiver::class.java, 340, 70, b.copy(missionsDone = 0, missionsTotal = 0, missionNext = "", collect = emptyList())),
                )
            }
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
        val provider: Class<*> = when (intent.getStringExtra("provider")) {
            "pet" -> PetCatWidgetReceiver::class.java; "clock" -> ClockWidgetReceiver::class.java; "friends" -> FriendsWidgetReceiver::class.java; "cafe" -> CafeWidgetReceiver::class.java
            "constellation" -> ConstellationWidgetReceiver::class.java; "weather" -> WeatherWidgetReceiver::class.java; "streak" -> StreakWidgetReceiver::class.java
            "goal" -> GoalWidgetReceiver::class.java; "missions" -> MissionsWidgetReceiver::class.java; else -> WindowWidgetReceiver::class.java
        }
        val demos = if (live) listOf(Demo("live", provider, intent.getIntExtra("w", 340), intent.getIntExtra("h", 150), null)) else sets(intent.getStringExtra("set") ?: "window").let { all -> intent.getIntExtra("part", -1).let { k -> if (k < 0) all else all.chunked(intent.getIntExtra("per", 4)).getOrElse(k) { emptyList() } } } // `--ei part N`: four at a time, so a page never needs scrolling
        val bound = ArrayList<Int>()
        for (dm in demos) {
            val id = host.allocateAppWidgetId()
            // The host view pads a widget by 8 dp on every side, as a launcher's cell does, and its updateAppWidgetSize takes the cell's
            // size and takes the padding off itself: the widget gets what's left (so a 340 x 150 cell hands it 324 x 134).
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
            view.updateAppWidgetSize(opts, dm.w, dm.h, dm.w, dm.h)
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
