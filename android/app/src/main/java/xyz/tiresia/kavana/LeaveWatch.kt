package xyz.tiresia.kavana

import android.Manifest
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import xyz.tiresia.kavana.LeaveRules.Move
import xyz.tiresia.kavana.LeaveRules.Verdict

/**
 * Picked apps for the leave cat ("The cat comes for"). While Chataltul is behind during a block, it reads which
 * app is in front from Usage access (UsageStatsManager's ACTIVITY_RESUMED events, about once a second from
 * FocusService) and LeaveRules says whether that brings the cat, takes it away, or neither.
 * With no list, or without Usage access, it stays out of the way: Chataltul going behind is leaving (Repo.left).
 * The list and the app in front stay on this phone: a prefs file of their own, kept out of backups
 * (data_extraction_rules.xml), never in the doc, and no package names in a release build's log.
 */
object LeaveWatch {
    private const val TAG = "ChataltulLeave"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("leave", Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = prefs(ctx).getString("mode", null) ?: LeaveRules.EVERY
    fun picked(ctx: Context): Set<String> = prefs(ctx).getStringSet("apps", null)?.toSet() ?: emptySet()

    fun set(ctx: Context, mode: String? = null, apps: Set<String>? = null) = prefs(ctx).edit().apply {
        if (mode != null) putString("mode", mode)
        if (apps != null) putStringSet("apps", apps)
    }.apply()

    /** Settings' row: "Every app", "Except 3", "Only 3". */
    fun summary(ctx: Context): String {
        val m = mode(ctx)
        val n = picked(ctx).size
        return if (!LeaveRules.listOn(m, picked(ctx))) "Every app" else if (m == LeaveRules.ONLY) "Only $n" else "Except $n"
    }

    fun usageOn(ctx: Context): Boolean {
        val m = ctx.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return if (m == AppOpsManager.MODE_DEFAULT) ctx.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        else m == AppOpsManager.MODE_ALLOWED
    }

    /** The list decides, and can see the app in front. */
    fun on(ctx: Context) = LeaveRules.listOn(mode(ctx), picked(ctx)) && usageOn(ctx)

    class App(val pkg: String, val label: String)

    /** The picker's apps: everything with a launcher icon, less the ones that never count. */
    fun launchable(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val never = never(ctx)
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName !in never }
            .distinctBy { it.activityInfo.packageName }
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    /** Apps that are never leaving (LeaveRules.judge): the home screen, calls, System UI, permission dialogs, the keyboard, Chataltul. */
    fun never(ctx: Context): Set<String> = buildSet {
        val pm = ctx.packageManager
        add(ctx.packageName)
        addAll(listOf("android", "com.android.systemui", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.google.android.packageinstaller", "com.android.packageinstaller"))
        addAll(listOf("com.android.server.telecom", "com.android.incallui", "com.samsung.android.incallui",
            "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer"))
        runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        // The home screen: the default one, or every one while none is picked. Not Settings' FallbackHome, which is HOME too.
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (def != null && def != "android") add(def)
        else pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.filter { it != "com.android.settings" }.forEach(::add)
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')?.let(::add)
        runCatching { ctx.getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { add(it.packageName) } }
    }

    private fun inCall(ctx: Context) = ctx.getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL

    // ---------- while Chataltul is behind ----------

    @Volatile private var watching = false
    private val resumed = LinkedHashMap<String, String>() // "package/activity" -> package, resumed and not paused since; the latest last
    private var readTo = 0L // events are read up to here (and the last few seconds again, in case one came late)
    private var seen = HashSet<String>() // the events of those few seconds, already counted
    private var tripAt = 0L // one of Chataltul's own trips: the first app after this is that trip's, and never counts
    private var tripApp: String? = null
    private var neverSet: Set<String> = emptySet()
    private var lastAllowed: String? = null
    private var lastSaid: String? = null

    /** Chataltul went behind with the screen on (or the phone was unlocked into another app). */
    fun behind(ctx: Context, at: Long) {
        if (!on(ctx)) return KavanaApp.repo.left(at)
        if (!watching) start(ctx, at, trip = false)
    }

    /** Chataltul sent you to Settings or a link (`at`: the tap): no cat for that app, but going on from it to another is judged as usual. */
    fun trip(ctx: Context, at: Long) {
        if (on(ctx)) start(ctx, at, trip = true)
    }

    /** A block started without Chataltul on the screen (a widget, a tile, the watch): the apps count from now. */
    fun started(ctx: Context) {
        if (!watching && !MainActivity.visible && on(ctx)) start(ctx, System.currentTimeMillis(), trip = false, lookBack = 30 * 60_000L)
    }

    /** `lookBack`: how far back to read for the app in front (Chataltul just went behind: a moment). */
    private fun start(ctx: Context, at: Long, trip: Boolean, lookBack: Long = 10_000L) {
        resumed.clear()
        seen = HashSet()
        readTo = at - lookBack + OVERLAP
        tripAt = if (trip) at else 0L
        tripApp = null
        lastAllowed = null
        lastSaid = null
        neverSet = never(ctx)
        watching = true
    }

    /** Back in Chataltul. */
    fun stop() {
        watching = false
        lastAllowed = null
    }

    /** About once a second, from FocusService while a block runs. */
    fun poll(ctx: Context) {
        if (!watching || MainActivity.visible) return
        val repo = KavanaApp.repo
        val st = repo.state.value ?: return
        val a = st.view.active ?: return
        if (!st.catsOn || a.kitten != "growing") return
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive || ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val now = System.currentTimeMillis()
        if (!on(ctx)) { // Usage access taken away while Chataltul was behind: today's way
            watching = false
            if (tripAt == 0L) repo.left(now)
            return
        }
        val front = front(ctx, now) ?: return
        val v = LeaveRules.judge(mode(ctx), picked(ctx), front, neverSet + listOfNotNull(tripApp), inCall(ctx))
        if (v == Verdict.ALLOWED) lastAllowed = front
        if (BuildConfig.DEBUG && front != lastSaid) { lastSaid = front; Log.i(TAG, "in front: $front, $v") }
        when (LeaveRules.move(v, away = repo.prefs.getLong("left", 0) != 0L)) {
            Move.LEAVE -> repo.left(now)
            Move.BACK -> repo.back(now, cat = "leave")
            Move.NONE -> {}
        }
    }

    private const val OVERLAP = 3_000L

    /** The app in front: the one resumed last and not paused since (an app in front for a while sends nothing new, and still is). */
    private fun front(ctx: Context, now: Long): String? {
        val events = runCatching { ctx.getSystemService(UsageStatsManager::class.java).queryEvents(readTo - OVERLAP, now + 1) }.getOrNull() ?: return null
        val next = HashSet<String>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val p = e.packageName ?: continue
            val key = "$p/${e.className}"
            val id = "${e.timeStamp}/${e.eventType}/$key"
            if (e.timeStamp >= now - OVERLAP) next += id
            if (id in seen) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    resumed.remove(key); resumed[key] = p
                    if (tripAt != 0L && tripApp == null && p !in neverSet && e.timeStamp >= tripAt) tripApp = p // (past a chooser)
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> resumed.remove(key)
            }
        }
        seen = next
        readTo = now
        return resumed.values.lastOrNull()
    }

    /** "Back to work" on the cat: the last allowed app you were in, if there was one (false: Chataltul, then). */
    fun backToWork(ctx: Context): Boolean {
        val p = lastAllowed?.takeIf { watching } ?: return false
        val i = ctx.packageManager.getLaunchIntentForPackage(p) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }.isSuccess
    }
}
